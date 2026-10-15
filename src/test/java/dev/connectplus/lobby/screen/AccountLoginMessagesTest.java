package dev.connectplus.lobby.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountLoginMessagesTest {
    @Test
    void saveLoginTrustWarningIsRedInBothLanguages() {
        final String key = "MainScreen.SaveLoginInfo.TrustWarning";
        final String chinese = Languages.builtInChinese().get(key);
        final String english = Languages.builtInEnglish().get(key);
        assertNotNull(chinese);
        assertNotNull(english);
        assertTrue(chinese.startsWith("§c"));
        assertTrue(english.startsWith("§c"));
        assertTrue(chinese.contains("账户会保留在当前服务器上"));
        assertTrue(chinese.contains("请确保这是你信任的服务器"));
    }
}
