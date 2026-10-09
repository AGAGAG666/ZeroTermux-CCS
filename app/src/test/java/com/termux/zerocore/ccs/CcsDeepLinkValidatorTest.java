package com.termux.zerocore.ccs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CcsDeepLinkValidatorTest {
    @Test public void acceptsCodexProviderImportShape() {
        assertTrue(CcsDeepLinkValidator.isSupported(
            "ccswitch://v1/import?resource=provider&app=codex"
                + "&name=My+Codex&endpoint=https%3A%2F%2Fapi.example.test%2Fv1"
                + "&apiKey=sk-test-placeholder&model=big-pickle"
                + "&homepage=https%3A%2F%2Fexample.test&enabled=true"));
    }

    @Test public void rejectsOtherSchemesHostsAndPaths() {
        assertFalse(CcsDeepLinkValidator.isSupported(
            "https://v1/import?resource=provider"));
        assertFalse(CcsDeepLinkValidator.isSupported(
            "ccswitch://v2/import?resource=provider"));
        assertFalse(CcsDeepLinkValidator.isSupported(
            "ccswitch://v1/other?resource=provider"));
    }

    @Test public void requiresAResourceAndRejectsEmptyInput() {
        assertFalse(CcsDeepLinkValidator.isSupported(
            "ccswitch://v1/import?app=codex"));
        assertFalse(CcsDeepLinkValidator.isSupported(null));
        assertFalse(CcsDeepLinkValidator.isSupported(""));
    }
}
