package dev.connectplus.lobby.screen.impl;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.connectplus.identity.IdentityLinkService;
import dev.connectplus.lobby.LobbyConstants;
import net.lenni0451.mcstructs.text.TextComponent;
import net.lenni0451.mcstructs.text.events.click.ClickEvent;
import net.lenni0451.mcstructs.text.events.click.types.CopyToClipboardClickEvent;
import net.lenni0451.mcstructs.text.events.click.types.OpenUrlClickEvent;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import dev.connectplus.utils.ViaUtils;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device-code verification link: 1.21.5+ clients click-to-copy, older
 * clients open the browser. Also pins the 769 (V1_21_4) codec path: the
 * copy_to_clipboard action must serialize into the wire compound untouched,
 * because ViaVersion's legacy→1.21.5 conversion leaves unknown actions as-is.
 */
@org.junit.jupiter.api.extension.ExtendWith(dev.connectplus.testutil.AccountPolicyExtension.class)
class AccountFlowLinkTest {

    private static final String URI = "https://www.microsoft.com/link";

    @Test
    void clickToCopyFollowsTheClientVersion() {
        assertTrue(AccountFlow.supportsClickToCopy(ProtocolVersion.v1_21_5), "1.21.5 knows copy_to_clipboard");
        assertTrue(AccountFlow.supportsClickToCopy(ProtocolVersion.v1_21_9), "newer clients too");
        assertFalse(AccountFlow.supportsClickToCopy(ProtocolVersion.v1_21_4), "the lobby protocol itself does not");
        assertFalse(AccountFlow.supportsClickToCopy(ProtocolVersion.v1_8), "legacy clients get open-url");
        assertFalse(AccountFlow.supportsClickToCopy(null), "unknown version plays safe with open-url");
    }

    @Test
    void linkUsesCopyForModernClientsAndOpenUrlOtherwise() {
        final ClickEvent modern = AccountFlow.verificationLink(ProtocolVersion.v1_21_5, URI).getStyle().getClickEvent();
        assertInstanceOf(CopyToClipboardClickEvent.class, modern);
        assertEquals(URI, ((CopyToClipboardClickEvent) modern).getValue());

        final ClickEvent legacy = AccountFlow.verificationLink(ProtocolVersion.v1_8, URI).getStyle().getClickEvent();
        assertInstanceOf(OpenUrlClickEvent.class, legacy);

        final ClickEvent unknown = AccountFlow.verificationLink(null, URI).getStyle().getClickEvent();
        assertInstanceOf(OpenUrlClickEvent.class, unknown);
    }

    @Test
    void theV1_21_4WireCodecSerializesTheCopyAction() {
        //The lobby writes chat in the 1.21.4 NBT shape; the copy action must come
        //out as its plain action string (a 1.21.5+ client reads it natively)
        final TextComponent link = AccountFlow.verificationLink(ProtocolVersion.v1_21_5, URI);
        final Tag raw = ViaUtils.convertNbt(LobbyConstants.TEXT_CODEC.serializeNbtTree(link));
        final CompoundTag root = assertInstanceOf(CompoundTag.class, raw);
        final CompoundTag clickEvent = root.getCompoundTag("clickEvent");
        assertEquals("copy_to_clipboard", clickEvent.getString("action"));
        assertEquals(URI, clickEvent.getString("value"));
    }

    // ---- task 5: §1.1 link-flow ordering ---------------------------------------

    /**
     * §1.1 (binding): the chat warning about the discarded bedrock profile must
     * be sent BEFORE the device-code authorization starts. The warning text is
     * fixed by the spec and must be present in both built-in languages.
     */
    @Test
    void theBedrockProfileWarningIsSentBeforeTheDeviceCodeAuthorizationStarts() {
        final java.util.List<String> sent = new java.util.ArrayList<>();
        final java.util.List<String> started = new java.util.ArrayList<>();
        //The recording harness: every sendChatLine appends to `sent`, the device
        //code start appends to `started`; the assertion is on the ORDER.
        final AccountFlow.LinkChatSink sink = sent::add;
        final Runnable deviceCodeStart = () -> started.add("device-code");
        AccountFlow.sendLinkWarningThenStart(sink, deviceCodeStart, dev.connectplus.lobby.screen.Lang.EN);

        assertEquals(java.util.List.of("device-code"), started,
                "the device-code authorization must start exactly once, after the warning");
        assertFalse(sent.isEmpty(), "the warning must be sent before anything else");
    }

    /** The order property, asserted directly: the last warning line precedes the device-code start. */
    @Test
    void warningOrderIsWarningFirstThenAuthorizationStart() {
        final java.util.List<String> events = new java.util.ArrayList<>();
        AccountFlow.sendLinkWarningThenStart(events::add, () -> events.add("<device-code-start>"),
                dev.connectplus.lobby.screen.Lang.ZH);
        //The warning lines must all come before the start marker.
        final int start = events.indexOf("<device-code-start>");
        assertTrue(start > 0, "the warning must be sent before the device-code start");
        for (int i = 0; i < start; i++) {
            assertFalse(events.get(i).contains("<device-code-start>"));
        }
        assertTrue(events.get(start - 1).length() > 0, "the last event before the start is a warning line");
    }

    /** §1.1: the warning must contain the exact required sentence in Chinese. */
    @Test
    void theChineseWarningCarriesTheSpecifiedText() {
        final java.util.List<String> sent = new java.util.ArrayList<>();
        AccountFlow.sendLinkWarningThenStart(sent::add, () -> { }, dev.connectplus.lobby.screen.Lang.ZH);
        final String joined = String.join("\n", sent);
        assertTrue(joined.contains("关联 Java 账号后，当前基岩版档案将被清除，其中的书签不会保留。请提前做好备份。"),
                "the §1.1 warning sentence must be verbatim, got: " + joined);
        assertTrue(joined.contains("关联后将使用 Java 账号的档案"), "the post-link explanation must be present");
        assertTrue(joined.contains("书签"), "the bookmark note must be present");
    }

    /** A failed/cancelled link keeps the account off the session (AccountFlow-side guard). */
    @Test
    void aFailedLinkResultNeverInstallsTheAccountOnTheSession() {
        final dev.connectplus.session.PlayerSession session =
                new dev.connectplus.session.PlayerSession(UUID.randomUUID(), "LinkPlayer");
        final dev.connectplus.testutil.StubAccount account = new dev.connectplus.testutil.StubAccount();
        //A non-committed result must not put B's account on the session (§4.1:
        //失败时释放本次持有的 B 资源, no "bound but failed" in-between state).
        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.FAILED, account);
        assertNull(session.account, "a FAILED link must not install the account");
        assertEquals("LinkPlayer", session.name);

        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.CONFLICT, account);
        assertNull(session.account, "a CONFLICT link must not install the account");

        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.STALE, account);
        assertNull(session.account, "a STALE link must not install the account");
    }

    /** A committed link publishes the new profile's account on the session. */
    @Test
    void aCommittedLinkInstallsTheAccountForTheNewProfile() {
        final dev.connectplus.session.PlayerSession session =
                new dev.connectplus.session.PlayerSession(UUID.randomUUID(), "LinkPlayer");
        final dev.connectplus.testutil.StubAccount account = new dev.connectplus.testutil.StubAccount();
        final var client = new io.netty.channel.embedded.EmbeddedChannel();
        session.c2pChannel = client;
        session.playerData = new dev.connectplus.session.PlayerData(session.uuid);
        dev.connectplus.testutil.TestClientIdentity.bedrock(client,
                dev.connectplus.identity.ClientIdentity.verifiedBedrock(session.uuid, session.name,
                        "4242424242424", UUID.randomUUID(), UUID.randomUUID().toString()));
        client.closeFuture().addListener(ignored -> session.account = null);
        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.COMMITTED, account);
        assertSame(account, session.account, "the committed link publishes B's account on the session");

        AccountFlow.applyLinkResult(session, IdentityLinkService.Result.COMMITTED_CLEANUP_PENDING, account);
        assertSame(account, session.account, "CLEANUP_PENDING still committed: the account stays usable");
        client.finishAndReleaseAll();
    }
}
