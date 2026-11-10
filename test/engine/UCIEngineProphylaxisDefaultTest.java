package engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Focused checks for the UCI default and explicit A/B option control. */
public final class UCIEngineProphylaxisDefaultTest {
    public static void main(String[] args) throws Exception {
        UCIEngine engine = new UCIEngine();
        Field enabledField = UCIEngine.class.getDeclaredField("prophylaxisProbeEnabled");
        enabledField.setAccessible(true);
        check(!enabledField.getBoolean(engine), "UCI engine must default the probe to disabled");

        Method handleCommand = UCIEngine.class.getDeclaredMethod("handleCommand", String.class);
        handleCommand.setAccessible(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output));
            handleCommand.invoke(engine, "uci");
        } finally {
            System.setOut(originalOut);
        }
        check(output.toString("UTF-8").contains(
                        "option name ProphylaxisProbe type check default false"),
                "UCI advertisement must report the disabled default");

        Field searchField = UCIEngine.class.getDeclaredField("search");
        searchField.setAccessible(true);
        Field searchEnabledField = Search.class.getDeclaredField("prophylaxisProbeEnabled");
        searchEnabledField.setAccessible(true);
        handleCommand.invoke(engine, "setoption name ProphylaxisProbe value true");
        check(enabledField.getBoolean(engine), "explicit true must enable the UCI option");
        check(searchEnabledField.getBoolean(searchField.get(engine)),
                "explicit true must enable probing in Search");
        handleCommand.invoke(engine, "setoption name Hash value 1");
        check(enabledField.getBoolean(engine), "Hash recreation must retain the UCI option state");
        check(searchEnabledField.getBoolean(searchField.get(engine)),
                "Hash recreation must apply the enabled state to the replacement Search");
        handleCommand.invoke(engine, "setoption name ProphylaxisProbe value false");
        check(!enabledField.getBoolean(engine), "explicit false must disable the UCI option");
        check(!searchEnabledField.getBoolean(searchField.get(engine)),
                "explicit false must disable probing in Search");

        System.out.println("UCIEngineProphylaxisDefaultTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
