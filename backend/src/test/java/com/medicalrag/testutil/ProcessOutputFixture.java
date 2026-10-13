package com.medicalrag.testutil;

/** Child JVM used only to exercise pipe draining and process shutdown. */
public class ProcessOutputFixture {
    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("linger")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(arguments[1] + "-SHUTDOWN")));
        }
        if (arguments[0].equals("large")) {
            for (int i = 0; i < 16384; i++) System.out.println("isolated-output-" + i);
        }
        System.out.println(arguments[1] + "-STDOUT");
        System.err.println(arguments[1] + "-STDERR");
        System.out.flush();
        System.err.flush();
        if (arguments[0].equals("linger")) Thread.sleep(120000);
    }
}
