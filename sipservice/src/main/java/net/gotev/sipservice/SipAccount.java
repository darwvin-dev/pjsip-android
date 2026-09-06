package net.gotev.sipservice;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnInstantMessageParam;
import org.pjsip.pjsua2.OnInstantMessageStatusParam;
import org.pjsip.pjsua2.OnTypingIndicationParam;
import org.pjsip.pjsua2.OnMwiInfoParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.SipRxData;
import org.pjsip.pjsua2.pjsip_status_code;

import java.util.HashMap;
import java.util.Set;

import static net.gotev.sipservice.ObfuscationHelper.getValue;

/**
 * Wrapper around PJSUA2 Account object.
 * @author gotev (Aleksandar Gotev)
 */
public class SipAccount extends Account {

    private static final String LOG_TAG = SipAccount.class.getSimpleName();

    private final HashMap<Integer, SipCall> activeCalls = new HashMap<>();
    private final SipAccountData data;
    private final SipService service;
    private boolean isGuest = false;

    protected SipAccount(SipService service, SipAccountData data) {
        super();
        this.service = service;
        this.data = data;
    }

    public SipService getService() {
        return service;
    }

    public SipAccountData getData() {
        return data;
    }

    public void create() throws Exception {
        create(data.getAccountConfig());
    }

    public void createGuest() throws Exception {
        isGuest = true;
        create(data.getGuestAccountConfig());
    }

    protected void removeCall(int callId) {
        SipCall call = activeCalls.get(callId);

        if (call != null) {
            Logger.debug(LOG_TAG, "Removing call with ID: " + callId);
            activeCalls.remove(callId);
        }

        if (isGuest) {
            service.removeGuestAccount();
            delete();
        }
    }

    public SipCall getCall(int callId) {
        return activeCalls.get(callId);
    }

    public Set<Integer> getCallIDs() {
        return activeCalls.keySet();
    }

    public SipCall addIncomingCall(int callId) {

        SipCall call = new SipCall(this, callId);
        activeCalls.put(callId, call);
        Logger.debug(LOG_TAG, "Added incoming call with ID " + callId
                + " to " + getValue(service.getApplicationContext(), data.getIdUri())
        );
        return call;
    }

    public SipCall addOutgoingCall(final String numberToDial, boolean isVideo, boolean isVideoConference, boolean isTransfer) {

        // PJSIP supports several simultaneous calls per account. The previous wrapper-level
        // global call-count guard artificially forced the service into single-call mode and
        // also prevented call waiting. Keep every dialog in activeCalls and let the app decide
        // which call is foreground/held.
        SipCall call = new SipCall(this);
        call.setVideoParams(isVideo, isVideoConference);

        CallOpParam callOpParam = new CallOpParam();
        try {
            if (numberToDial.startsWith("sip:") || numberToDial.startsWith("sips:")) {
                call.makeCall(numberToDial, callOpParam);
            } else {
                if ("*".equals(data.getRealm())) {
                    call.makeCall("sip:" + numberToDial, callOpParam);
                } else {
                    call.makeCall("sip:" + numberToDial + "@" + data.getRealm(), callOpParam);
                }
            }
            activeCalls.put(call.getId(), call);
            Logger.debug(LOG_TAG, "New outgoing call with ID: " + call.getId());

            return call;

        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Error while making outgoing call", exc);
            return null;
        }
    }

    public SipCall addOutgoingCall(final String numberToDial) {
        return addOutgoingCall(numberToDial, false, false, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SipAccount that = (SipAccount) o;

        return data.equals(that.data);

    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        Logger.info(LOG_TAG, "Sip Reg Info - Code: " + prm.getCode() +
                ", Reason: " + prm.getReason() + ", Exp: " + prm.getExpiration() + ", Status: " + prm.getStatus()
        );
        service.getBroadcastEmitter().registrationState(data.getIdUri(), prm.getCode());
    }

    @Override
    public void onMwiInfo(OnMwiInfoParam prm) {
        // Unsolicited MWI NOTIFY (RFC 3842). Parse the voice-message counts from the raw
        // message body and broadcast them so the app can drive the voicemail badge.
        try {
            SipRxData rdata = prm.getRdata();
            String wholeMsg = rdata != null ? rdata.getWholeMsg() : null;
            VoicemailStatus status = VoicemailStatus.parse(wholeMsg);
            Logger.info(LOG_TAG, "Received MWI info - " + status);
            service.getBroadcastEmitter().voicemailWaiting(data.getIdUri(), status);
        } catch (Exception ex) {
            Logger.error(LOG_TAG, "Error while handling MWI info", ex);
        }
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {

        SipCall call = addIncomingCall(prm.getCallId());

        // Local DND: decline with 486 Busy Here (a 4XX — NOT 603 Decline).
        // 486 is local-only, so the PBX keeps ringing the user's other
        // devices; a 6XX would tell the PBX to terminate the call on every
        // device, which would defeat the "this device only" semantics.
        if (service.isDND()) {
            try {
                CallerInfo contactInfo = new CallerInfo(call.getInfo());
                service.getBroadcastEmitter().missedCall(contactInfo.getDisplayName(), contactInfo.getRemoteUri());
                call.declineIncomingCall(pjsip_status_code.PJSIP_SC_BUSY_HERE);
                Logger.debug(LOG_TAG, "DND - Decline call with ID: " + prm.getCallId());
            } catch(Exception ex) {
                Logger.error(LOG_TAG, "Error while getting -missed because declined- call info", ex);
            }
            return;
        }

        // Do not reject a second INVITE merely because another dialog exists. DarwPhone
        // implements call waiting in the app layer: the established call can stay held while
        // this one rings, and the user chooses which dialog to answer.

        try {
            // Answer with 180 Ringing
            CallOpParam callOpParam = new CallOpParam();
            callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
            call.answer(callOpParam);
            Logger.debug(LOG_TAG, "Sending 180 ringing");

            String displayName, remoteUri;
            try {
                CallerInfo contactInfo = new CallerInfo(call.getInfo());
                displayName = contactInfo.getDisplayName();
                remoteUri = contactInfo.getRemoteUri();
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Error while getting caller info", ex);
                throw ex;
            }

            // check for video in remote SDP
            CallInfo callInfo = call.getInfo();
            boolean isVideo = (callInfo.getRemOfferer() && callInfo.getRemVideoCount() > 0);

            service.getBroadcastEmitter().incomingCall(data.getIdUri(), prm.getCallId(),
                            displayName, remoteUri, isVideo);

        } catch (Exception ex) {
            Logger.error(LOG_TAG, "Error while getting caller info", ex);
        }
    }
    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
        super.onInstantMessage(prm);
        service.getBroadcastEmitter().instantMessageReceived(
                data.getIdUri(),
                prm.getFromUri(),
                prm.getToUri(),
                prm.getContentType(),
                prm.getMsgBody());
    }

    @Override
    public void onInstantMessageStatus(OnInstantMessageStatusParam prm) {
        super.onInstantMessageStatus(prm);
        service.getBroadcastEmitter().instantMessageStatus(
                data.getIdUri(),
                prm.getToUri(),
                prm.getMsgBody(),
                prm.getCode(),
                prm.getReason());
    }

    @Override
    public void onTypingIndication(OnTypingIndicationParam prm) {
        super.onTypingIndication(prm);
        service.getBroadcastEmitter().typingIndication(
                data.getIdUri(),
                prm.getFromUri(),
                prm.getIsTyping());
    }

}
