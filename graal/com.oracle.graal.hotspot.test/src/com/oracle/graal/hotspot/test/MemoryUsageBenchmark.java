/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.hotspot.test;

import static com.oracle.graal.debug.internal.MemUseTrackerImpl.getCurrentThreadAllocatedBytes;
import jdk.internal.jvmci.hotspot.HotSpotJVMCIRuntime;
import jdk.internal.jvmci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.internal.jvmci.hotspot.HotSpotResolvedJavaMethod;

import com.oracle.graal.api.runtime.Graal;
import com.oracle.graal.compiler.test.AllocSpy;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugEnvironment;
import com.oracle.graal.debug.internal.DebugScope;
import com.oracle.graal.hotspot.CompilationTask;
import com.oracle.graal.hotspot.CompileTheWorld;
import com.oracle.graal.hotspot.CompileTheWorldOptions;
import com.oracle.graal.hotspot.HotSpotGraalCompiler;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;

/**
 * Used to benchmark memory usage during Graal compilation.
 *
 * To benchmark:
 *
 * <pre>
 *     mx vm -XX:-UseJVMCIClassLoader -cp @com.oracle.graal.hotspot.test com.oracle.graal.hotspot.test.MemoryUsageBenchmark
 * </pre>
 *
 * Memory analysis for a {@link CompileTheWorld} execution can also be performed. For example:
 *
 * <pre>
 *     mx --vm server vm -XX:-UseJVMCIClassLoader -G:CompileTheWorldClasspath=$HOME/SPECjvm2008/SPECjvm2008.jar -cp @com.oracle.graal.hotspot.test com.oracle.graal.hotspot.test.MemoryUsageBenchmark
 * </pre>
 */
public class MemoryUsageBenchmark extends HotSpotGraalCompilerTest {

    public static int simple(int a, int b) {
        return a + b;
    }

    public static synchronized int complex(CharSequence cs) {
        if (cs instanceof String) {
            return cs.hashCode();
        }

        if (cs instanceof StringBuilder) {
            int[] hash = {0};
            cs.chars().forEach(c -> hash[0] += c);
            return hash[0];
        }

        int res = 0;

        // Exercise lock elimination
        synchronized (cs) {
            res = cs.length();
        }
        synchronized (cs) {
            res = cs.hashCode() ^ 31;
        }

        for (int i = 0; i < cs.length(); i++) {
            res *= cs.charAt(i);
        }

        // A fixed length loop with some canonicalizable arithmetics will
        // activate loop unrolling and more canonicalization
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += i * 2;
        }
        res += sum;

        // Activates escape-analysis
        res += new String("asdf").length();

        return res;
    }

    static class MemoryUsageCloseable implements AutoCloseable {

        private final long start;
        private final String name;

        public MemoryUsageCloseable(String name) {
            this.name = name;
            this.start = getCurrentThreadAllocatedBytes();
        }

        @Override
        public void close() {
            long end = getCurrentThreadAllocatedBytes();
            long allocated = end - start;
            System.out.println(name + ": " + allocated);
        }
    }

    public static void main(String[] args) {
        // Ensure a Graal runtime is initialized prior to Debug being initialized as the former
        // may include processing command line options used by the latter.
        Graal.getRuntime();

        // Ensure a debug configuration for this thread is initialized
        if (Debug.isEnabled() && DebugScope.getConfig() == null) {
            DebugEnvironment.initialize(System.out);
        }
        new MemoryUsageBenchmark().run();
    }

    @SuppressWarnings("try")
    private void doCompilation(String methodName, String label) {
        HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) getResolvedJavaMethod(methodName);

        // invalidate any existing compiled code
        method.reprofile();

        int id = method.allocateCompileId(jdk.internal.jvmci.compiler.Compiler.INVOCATION_ENTRY_BCI);
        long graalEnv = 0L;

        try (MemoryUsageCloseable c = label == null ? null : new MemoryUsageCloseable(label)) {
            HotSpotJVMCIRuntimeProvider runtime = HotSpotJVMCIRuntime.runtime();
            CompilationTask task = new CompilationTask(runtime, (HotSpotGraalCompiler) runtime.getCompiler(), method, jdk.internal.jvmci.compiler.Compiler.INVOCATION_ENTRY_BCI, graalEnv, id, false);
            task.runCompilation();
        }
    }

    @SuppressWarnings("try")
    private void allocSpyCompilation(String methodName) {
        if (AllocSpy.isEnabled()) {
            HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) getResolvedJavaMethod(methodName);

            // invalidate any existing compiled code
            method.reprofile();

            int id = method.allocateCompileId(jdk.internal.jvmci.compiler.Compiler.INVOCATION_ENTRY_BCI);
            long graalEnv = 0L;
            try (AllocSpy as = AllocSpy.open(methodName)) {
                HotSpotJVMCIRuntimeProvider runtime = HotSpotJVMCIRuntime.runtime();
                CompilationTask task = new CompilationTask(runtime, (HotSpotGraalCompiler) runtime.getCompiler(), method, jdk.internal.jvmci.compiler.Compiler.INVOCATION_ENTRY_BCI, graalEnv, id,
                                false);
                task.runCompilation();
            }
        }
    }

    private static final boolean verbose = Boolean.getBoolean("verbose");

    private void compileAndTime(String methodName) {

        // Parse in eager mode to resolve methods/fields/classes
        parseEager(methodName, AllowAssumptions.YES);

        // Warm up and initialize compiler phases used by this compilation
        for (int i = 0; i < 10; i++) {
            doCompilation(methodName, verbose ? methodName + "[warmup-" + i + "]" : null);
        }

        doCompilation(methodName, methodName);
    }

    public void run() {
        compileAndTime("simple");
        compileAndTime("complex");
        if (CompileTheWorldOptions.CompileTheWorldClasspath.getValue() != CompileTheWorld.SUN_BOOT_CLASS_PATH) {
            HotSpotJVMCIRuntimeProvider runtime = HotSpotJVMCIRuntime.runtime();
            CompileTheWorld ctw = new CompileTheWorld(runtime, (HotSpotGraalCompiler) runtime.getCompiler());
            try {
                ctw.compile();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        allocSpyCompilation("simple");
        allocSpyCompilation("complex");
    }
}
