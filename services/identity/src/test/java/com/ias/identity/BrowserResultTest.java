package com.ias.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserResultTest {
    @Test void runningBodyWithSuccessLiteralInScriptMustFail() {
        String dom="<html><body><output id='browser-result' data-complete='false'>RUNNING</output><script>const success='BROWSER_PASS:';</script></body></html>";
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted(dom,0));
    }
    @Test void missingDuplicateFailedAndIncompleteResultsMustFail() {
        String success="<output id='browser-result' data-complete='true'>"+BrowserFlowTest.PASS+"</output>";
        BrowserFlowTest.assertCompleted(success,0);
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted(success,1));
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted(success+success,0));
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted("<script>"+BrowserFlowTest.PASS+"</script>",0));
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted(success.replace("data-complete='true'","data-complete='false'"),0));
        assertThrows(AssertionError.class,()->BrowserFlowTest.assertCompleted(success.replace(BrowserFlowTest.PASS,"BROWSER_FAIL: forced"),0));
    }
}
