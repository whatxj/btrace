/*
 * Copyright (c) 2008, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the Classpath exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.btrace.agent;

import com.sun.btrace.DebugSupport;
import com.sun.btrace.SharedSettings;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.jar.JarFile;
import com.sun.btrace.BTraceRuntime;
import com.sun.btrace.comm.ErrorCommand;
import com.sun.btrace.comm.OkayCommand;
import com.sun.btrace.util.Messages;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * This is the main class for BTrace java.lang.instrument agent.
 *
 * @author A. Sundararajan
 * @author Joachim Skeie (rolling output)
 */
public final class Main {
    private static volatile Map<String, String> argMap;
    private static volatile Instrumentation inst;
    private static volatile String scriptOutputFile;
    private static volatile Long fileRollMilliseconds;

    private static final SharedSettings settings = SharedSettings.GLOBAL;
    private static final DebugSupport debug = new DebugSupport(settings);

    // #BTRACE-42: Non-daemon thread prevents traced application from exiting
    private static final ThreadFactory daemonizedThreadFactory = new ThreadFactory() {
        ThreadFactory delegate = Executors.defaultThreadFactory();
        @Override
        public Thread newThread(Runnable r) {
            Thread result = delegate.newThread(r);
            result.setDaemon(true);
            return result;
        }
    };

    private static final ExecutorService serializedExecutor = Executors.newSingleThreadExecutor(daemonizedThreadFactory);

    public static void premain(String args, Instrumentation inst) {
        main(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        main(args, inst);
    }

    private static synchronized void main(final String args, final Instrumentation inst) {
        if (Main.inst != null) {
            return;
        } else {
            Main.inst = inst;
        }

        parseArgs(args);
        if (isDebug()) debugPrint("parsed command line arguments");

        String bootClassPath = argMap.get("bootClassPath");
        if (bootClassPath != null) {
            if (isDebug()) {
                 debugPrint("Bootstrap ClassPath: " + bootClassPath);
            }
            StringTokenizer tokenizer = new StringTokenizer(bootClassPath, File.pathSeparator);
            try {
                while (tokenizer.hasMoreTokens()) {
                    String path = tokenizer.nextToken();
                    File f = new File(path);
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                        inst.appendToBootstrapClassLoaderSearch(new JarFile(f));
                    } else {
                        debugPrint("ignoring boot classpath element '" + path +
                                   "' - only jar files allowed");
                    }
                }
            } catch (IOException ex) {
                debugPrint("adding to boot classpath failed!");
                debugPrint(ex);
                return;
            }
        }

        String systemClassPath = argMap.get("systemClassPath");
        if (systemClassPath != null) {
            if (isDebug()) {
                 debugPrint("System ClassPath: " + systemClassPath);
            }
            StringTokenizer tokenizer = new StringTokenizer(systemClassPath, File.pathSeparator);
            try {
                while (tokenizer.hasMoreTokens()) {
                    String path = tokenizer.nextToken();
                    File f = new File(path);
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                        inst.appendToSystemClassLoaderSearch(new JarFile(f));
                    } else {
                        debugPrint("ignoring system classpath element '" + path +
                                   "' - only jar files allowed");
                    }
                }
            } catch (IOException ex) {
                debugPrint("adding to boot classpath failed!");
                debugPrint(ex);
                return;
            }
        }

        startScripts();

        String tmp = argMap.get("noServer");
        boolean noServer = tmp != null && !"false".equals(tmp);
        if (noServer) {
            if (isDebug()) debugPrint("noServer is true, server not started");
            return;
        }
        Thread agentThread = new Thread(new Runnable() {
            @Override
            public void run() {
                BTraceRuntime.enter();
                try {
                    startServer();
                } finally {
                    BTraceRuntime.leave();
                }
            }
        });
        BTraceRuntime.initUnsafe();
        BTraceRuntime.enter();
        try {
            agentThread.setDaemon(true);
            if (isDebug()) debugPrint("starting agent thread");
            agentThread.start();
        } finally {
            BTraceRuntime.leave();
        }
    }

    private static void startScripts() {
        String p = argMap.get("stdout");
        boolean traceToStdOut = p != null && !"false".equals(p);
        if (isDebug()) debugPrint("stdout is " + traceToStdOut);

        p = argMap.get("script");
        if (p != null) {
            StringTokenizer tokenizer = new StringTokenizer(p, ",");

	    if (isDebug()) {
                debugPrint(((tokenizer.countTokens() == 1) ? "initial script is " : "initial scripts are " ) + p);
            }
            while (tokenizer.hasMoreTokens()) {
                loadBTraceScript(tokenizer.nextToken(), traceToStdOut);
            }
        }
        p = argMap.get("scriptdir");
        if (p != null) {
            File scriptdir = new File(p);
            if (scriptdir.isDirectory()) {
                if (isDebug()) debugPrint("found scriptdir: " + scriptdir.getAbsolutePath());
                File[] files = scriptdir.listFiles();
                if (files != null) {
                    for (File file : files) {
                       loadBTraceScript(file.getAbsolutePath(), traceToStdOut);
                    }
                }
            }
        }
    }

    private static void usage() {
        System.out.println(Messages.get("btrace.agent.usage"));
        System.exit(0);
    }


    private static void parseArgs(String args) {
        if (args == null) {
            args = "";
        }
        String[] pairs = args.split(",");
        argMap = new HashMap<>();
        for (String s : pairs) {
            int i = s.indexOf('=');
            String key, value = "";
            if (i != -1) {
                key = s.substring(0,i).trim();
                if (i+1 < s.length()) {
                    value = s.substring(i+1).trim();
                }
            } else {
                key = s;
            }
            argMap.put(key, value);
        }

        String p = argMap.get("help");
        if (p != null) {
            usage();
        }
        p = argMap.get("debug");
        settings.setDebug(p != null && !"false".equals(p));
        if (isDebug()) debugPrint("debugMode is " + settings.isDebug());

        p = argMap.get("dumpClasses");
        boolean dumpClasses = p != null && !"false".equals(p);
        if (isDebug()) debugPrint("dumpClasses is " + dumpClasses);
        if (dumpClasses) {
            String dumpDir = argMap.get("dumpDir");
            settings.setDumpDir(dumpDir != null ? dumpDir : ".");
            if (isDebug()) debugPrint("dumpDir is " + dumpDir);
        }

        p = argMap.get("cmdQueueLimit");
        if (p != null) {
            debugPrint("cmdQueueLimit provided: " + p);
            System.setProperty(BTraceRuntime.CMD_QUEUE_LIMIT_KEY, p);
        }

        p = argMap.get("trackRetransforms");
        settings.setTrackRetransforms(p != null && !"false".equals(p));
        if (settings.isTrackRetransforms()) debugPrint("trackRetransforms is " + settings.isTrackRetransforms());
        scriptOutputFile = argMap.get("scriptOutputFile");
        if (scriptOutputFile != null && scriptOutputFile.length() > 0) {
            if (isDebug()) debugPrint("scriptOutputFile is " + scriptOutputFile);
        }

        p = argMap.get("fileRollMilliseconds");
        if (p != null && p.length() > 0) {
            Long msParsed = null;
            try {
                msParsed = Long.parseLong(p);
                fileRollMilliseconds = msParsed;
            } catch (NumberFormatException nfe) {
                fileRollMilliseconds = null;
            }
            if (fileRollMilliseconds != null) {
                if (isDebug()) debugPrint("fileRollMilliseconds is " + fileRollMilliseconds);
            }
        }
	p = argMap.get("unsafe");
        settings.setUnsafe(p != null && "true".equals(p));
        if (isDebug()) debugPrint("unsafeMode is " + settings.isUnsafe());

        String statsdDef = argMap.get("statsd");
        if (statsdDef != null) {
            String[] parts = statsdDef.split(":");
            if (parts.length == 2) {
                settings.setStatsdHost(parts[0].trim());
                try {
                    settings.setStatsdPort(Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) {
                    debug.warning("Invalid statsd port number: " + parts[1]);
                    // leave the port unconfigured
                }
            } else if (parts.length == 1) {
                settings.setStatsdHost(parts[0].trim());
            }
        }

        String probeDescPath = argMap.get("probeDescPath");
        settings.setProbeDescPath(probeDescPath != null ? probeDescPath : ".");
        if (isDebug()) debugPrint("probe descriptor path is " + settings.getProbeDescPath());
    }

    // This is really a *private* interface to Glassfish monitoring.
    // For now, please avoid using this in any other scenario.
    public static void handleFlashLightClient(byte[] code, PrintWriter traceWriter) {
        handleNewClient(code, traceWriter);
    }

    // This is really a *private* interface to Glassfish monitoring.
    // For now, please avoid using this in any other scenario.
    public static void handleFlashLightClient(byte[] code) {
        try {
            String twn = "flashlighttrace" + (new Date()).getTime();
            PrintWriter traceWriter = null;
            traceWriter = new PrintWriter(new BufferedWriter(new FileWriter(new File(twn + ".btrace"))));
            handleFlashLightClient(code, traceWriter);
        } catch (IOException ioexp) {
            if (isDebug()) {
                debugPrint(ioexp);
            }
        }
    }

    private static void loadBTraceScript(String filename, boolean traceToStdOut) {
        try {
            if (! filename.endsWith(".class")) {
                if (isDebug()) {
                    debugPrint("refusing " + filename + ". script should be a pre-compiled .class file");
                }
                return;
            }
            File traceScript = new File(filename);
            if (! traceScript.exists()) {
                if (isDebug()) debugPrint("script " + traceScript + " does not exist!");
                return;
            }

            String traceOutput = null;
            if (traceToStdOut) {
                traceOutput = "::stdout";
            } else {
                String agentName = System.getProperty("btrace.agent", "default");
            	traceOutput = scriptOutputFile;

                if (traceOutput == null || traceOutput.length() == 0) {
                    traceOutput = filename + (agentName != null ? "." + agentName  : "") + ".${ts}.btrace";
                    if (isDebug()) debugPrint("scriptOutputFile not specified. defaulting to " + traceOutput);
                }
                traceOutput = templateFileName(traceOutput);
                if (isDebug()) debugPrint("Redirecting output to " + traceOutput);
            }

            Client client = new FileClient(inst, traceScript, traceOutput);

            handleNewClient(client).get();
        } catch (RuntimeException | IOException | ExecutionException re) {
            if (isDebug()) debugPrint(re);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String templateFileName(String fName) {
        if (fName != null) {
            fName = fName.replace("${ts}", String.valueOf(System.currentTimeMillis()));
        }
        return fName;
    }

    public static final int BTRACE_DEFAULT_PORT = 2020;

    //-- Internals only below this point
    private static void startServer() {
        int port = BTRACE_DEFAULT_PORT;
        String p = argMap.get("port");
        if (p != null) {
            try {
                port = Integer.parseInt(p);
            } catch (NumberFormatException exp) {
                error("invalid port assuming default..");
            }
        }
        ServerSocket ss;
        try {
            if (isDebug()) debugPrint("starting server at " + port);
            System.setProperty("btrace.port", String.valueOf(port));
            if (scriptOutputFile != null && scriptOutputFile.length() > 0) {
                System.setProperty("btrace.output", scriptOutputFile);
            }
            ss = new ServerSocket(port);
        } catch (IOException ioexp) {
            ioexp.printStackTrace();
            return;
        }

        while (true) {
            try {
                if (isDebug()) debugPrint("waiting for clients");
                Socket sock = ss.accept();
                if (isDebug()) debugPrint("client accepted " + sock);
                Client client = new RemoteClient(inst, sock);
                handleNewClient(client).get();
            } catch (RuntimeException | IOException | ExecutionException re) {
                if (isDebug()) debugPrint(re);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void handleNewClient(byte[] code, PrintWriter traceWriter) {
        try {
            handleNewClient(new FileClient(inst, code, traceWriter));
        } catch (RuntimeException | IOException re) {
            if (isDebug()) {
                debugPrint(re);
            }
        }
    }

    private static Future<?> handleNewClient(final Client client) {
        return serializedExecutor.submit(new Runnable() {

            @Override
            public void run() {
                boolean entered = BTraceRuntime.enter();
                try {
                    client.debugPrint("new Client created " + client);
                    if (client.shouldAddTransformer()) {
                        client.registerTransformer();
                        ArrayList<Class> list = new ArrayList<>();
                        client.debugPrint("filtering loaded classes");
                        ClassCache cc = ClassCache.getInstance();
                        for (Class c : inst.getAllLoadedClasses()) {
                            if (c != null) {
                                cc.get(c);
                                if (inst.isModifiableClass(c) &&
                                    client.isCandidate(c)) {
                                    client.debugPrint("candidate " + c + " added");
                                    list.add(c);
                                }
                            }
                        }
                        list.trimToSize();
                        int size = list.size();
                        client.debugPrint("added as ClassFileTransformer");
                        if (size > 0) {
                            Class[] classes = new Class[size];
                            list.toArray(classes);
                            client.startRetransformClasses(size);
                            if (isDebug()) {
                                for(Class c : classes) {
                                    try {
                                        inst.retransformClasses(c);
                                    } catch (VerifyError e) {
                                        client.debugPrint("verification error: " + c.getName());
                                    }
                                }
                            } else {
                                inst.retransformClasses(classes);
                            }
                            client.skipRetransforms();
                        }
                    }
                    client.getRuntime().send(new OkayCommand());
                } catch (UnmodifiableClassException uce) {
                    if (isDebug()) {
                        debugPrint(uce);
                    }
                    client.getRuntime().send(new ErrorCommand(uce));
                } finally {
                    if (entered) {
                        BTraceRuntime.leave();
                    }
                }
            }
        });

    }

    private static void error(String msg) {
        System.err.println("btrace ERROR: " + msg);
    }

    private static boolean isDebug() {
        return settings.isDebug();
    }

    private static void debugPrint(String msg) {
        debug.print(msg);
    }

    private static void debugPrint(Throwable th) {
        debug.print(th);
    }
}
