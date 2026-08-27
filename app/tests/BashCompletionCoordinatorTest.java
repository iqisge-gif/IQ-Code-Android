import com.termux.app.iqcode.termux.BashCompletionCoordinator;

public final class BashCompletionCoordinatorTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String token = "a1b2c3";
        require(BashCompletionCoordinator.parseControlLine(token + ":0", token).intValue() == 0,
            "valid control records must preserve zero exit status");
        require(BashCompletionCoordinator.parseControlLine(token + ":127", token).intValue() == 127,
            "valid control records must preserve nonzero exit status");
        require(BashCompletionCoordinator.parseControlLine("wrong:0", token) == null,
            "foreign control tokens must be ignored");
        require(BashCompletionCoordinator.parseControlLine(token + ":256", token) == null,
            "out-of-range control exit statuses must be rejected");
        require(BashCompletionCoordinator.parseControlLine(token + ":abc", token) == null,
            "malformed control records must be rejected");

        BashCompletionCoordinator processFirst = new BashCompletionCoordinator();
        require(processFirst.publishProcessExit(23), "process exit must publish exactly once");
        require(processFirst.await(0) && processFirst.processExited(),
            "a real process exit must complete the coordinator");
        require(processFirst.exitCode() == 23 && processFirst.source() == BashCompletionCoordinator.Source.PROCESS,
            "the actual process exit must supply the returned status");
        require(!processFirst.publishControl(0),
            "a late control marker must not replace the real process result");

        BashCompletionCoordinator controlFirst = new BashCompletionCoordinator();
        require(controlFirst.publishControl(9), "valid control completion must publish exactly once");
        require(controlFirst.await(0) && !controlFirst.processExited(),
            "control completion may precede wrapper process exit");
        require(controlFirst.exitCode() == 9 && controlFirst.source() == BashCompletionCoordinator.Source.CONTROL,
            "control completion must preserve its status");
        require(!controlFirst.publishProcessExit(1) && controlFirst.processExited(),
            "later wrapper exit must be observed but cannot overwrite trusted control status");
        require(controlFirst.awaitProcessExit(0),
            "process-exit latch must close only after waitFor supplied a result");

        System.out.println("BashCompletionCoordinatorTest PASS");
    }
}
