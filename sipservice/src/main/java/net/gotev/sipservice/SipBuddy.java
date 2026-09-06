package net.gotev.sipservice;

import org.pjsip.pjsua2.Buddy;
import org.pjsip.pjsua2.BuddyConfig;
import org.pjsip.pjsua2.BuddyInfo;
import org.pjsip.pjsua2.OnBuddyEvSubStateParam;
import org.pjsip.pjsua2.PresenceStatus;
import org.pjsip.pjsua2.RxMsgEvent;
import org.pjsip.pjsua2.SipEvent;
import org.pjsip.pjsua2.SipEventBody;
import org.pjsip.pjsua2.SipRxData;
import org.pjsip.pjsua2.SendInstantMessageParam;
import org.pjsip.pjsua2.SendTypingIndicationParam;
import org.pjsip.pjsua2.pjsua_buddy_status;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DarwPhone-facing Buddy wrapper. PJSIP keeps presence and RFC 4235 dialog subscriptions on the
 * SIP thread; the Android app receives only small, sanitized state broadcasts.
 */
final class SipBuddy extends Buddy {

    private static final Pattern DIALOG_STATE = Pattern.compile(
            "<state(?:\\s[^>]*)?>\\s*([^<]+)\\s*</state>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIALOG_TAG = Pattern.compile(
            "<dialog(?:\\s|>)",
            Pattern.CASE_INSENSITIVE);

    private final SipAccount account;
    private final String uri;
    private final boolean dialogEvent;
    private String lastDialogState = "unknown";

    SipBuddy(SipAccount account, String uri, boolean dialogEvent) {
        super();
        this.account = account;
        this.uri = uri;
        this.dialogEvent = dialogEvent;
    }

    void startSubscription() throws Exception {
        BuddyConfig config = new BuddyConfig();
        try {
            config.setUri(uri);
            // PJSUA2 allows one event package per Buddy. Presence and BLF therefore use separate
            // Buddy instances even when they point at the same AOR.
            config.setSubscribe(!dialogEvent);
            config.setSubscribe_dlg_event(dialogEvent);
            create(account, config);
        } finally {
            config.delete();
        }

        if (dialogEvent) {
            updateDlgEvent();
        } else {
            updatePresence();
        }
    }

    void startMessaging() throws Exception {
        BuddyConfig config = new BuddyConfig();
        try {
            config.setUri(uri);
            config.setSubscribe(false);
            config.setSubscribe_dlg_event(false);
            create(account, config);
        } finally {
            config.delete();
        }
    }

    void sendMessage(String content, String contentType) throws Exception {
        SendInstantMessageParam param = new SendInstantMessageParam();
        try {
            param.setContent(content == null ? "" : content);
            param.setContentType(
                    contentType == null || contentType.trim().isEmpty()
                            ? "text/plain"
                            : contentType.trim());
            sendInstantMessage(param);
        } finally {
            param.delete();
        }
    }

    void sendTyping(boolean typing) throws Exception {
        SendTypingIndicationParam param = new SendTypingIndicationParam();
        try {
            param.setIsTyping(typing);
            sendTypingIndication(param);
        } finally {
            param.delete();
        }
    }

    void stopSubscription() {
        try {
            if (dialogEvent) {
                subscribeDlgEvent(false);
            } else {
                subscribePresence(false);
            }
        } catch (Exception ignored) { }
        try { delete(); } catch (Exception ignored) { }
    }

    @Override
    public void onBuddyState() {
        super.onBuddyState();
        if (dialogEvent) return;
        try {
            BuddyInfo info = getInfo();
            try {
                PresenceStatus presence = info.getPresStatus();
                int status = presence == null
                        ? pjsua_buddy_status.PJSUA_BUDDY_STATUS_UNKNOWN
                        : presence.getStatus();
                String text = presence == null ? "" : safe(presence.getStatusText());
                String note = presence == null ? "" : safe(presence.getNote());
                account.getService().getBroadcastEmitter().presenceState(
                        account.getData().getIdUri(),
                        uri,
                        status,
                        text,
                        note,
                        safe(info.getSubStateName()),
                        info.getSubTermCode(),
                        safe(info.getSubTermReason()));
            } finally {
                info.delete();
            }
        } catch (Exception error) {
            Logger.error("SipBuddy", "Unable to read presence state", error);
        }
    }

    @Override
    public void onBuddyDlgEventState() {
        super.onBuddyDlgEventState();
        if (!dialogEvent) return;
        emitDialogState(lastDialogState);
    }

    @Override
    public void onBuddyEvSubDlgEventState(OnBuddyEvSubStateParam prm) {
        super.onBuddyEvSubDlgEventState(prm);
        if (!dialogEvent || prm == null) return;

        String raw = extractIncomingMessage(prm);
        if (raw == null || raw.isEmpty()) return;

        String state = parseDialogState(raw);
        if (state != null) {
            lastDialogState = state;
            emitDialogState(state);
        }
    }

    private void emitDialogState(String state) {
        account.getService().getBroadcastEmitter().blfState(
                account.getData().getIdUri(),
                uri,
                state == null ? "unknown" : state);
    }

    static String parseDialogState(String wholeMessage) {
        if (wholeMessage == null) return null;
        Matcher matcher = DIALOG_STATE.matcher(wholeMessage);
        if (matcher.find()) {
            return matcher.group(1).trim().toLowerCase(Locale.US);
        }

        // A valid empty dialog-info document means the monitored extension is idle.
        String lower = wholeMessage.toLowerCase(Locale.US);
        if (lower.contains("<dialog-info") && !DIALOG_TAG.matcher(wholeMessage).find()) {
            return "terminated";
        }
        return null;
    }

    private static String extractIncomingMessage(OnBuddyEvSubStateParam prm) {
        try {
            SipEvent event = prm.getE();
            if (event == null) return null;
            SipEventBody body = event.getBody();
            if (body == null) return null;
            RxMsgEvent rx = body.getRxMsg();
            if (rx == null) return null;
            SipRxData data = rx.getRdata();
            return data == null ? null : data.getWholeMsg();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
