package net.gotev.sipservice;

import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.OnIpChangeProgressParam;
import org.pjsip.pjsua2.OnTransportStateParam;
import org.pjsip.pjsua2.SslCertName;
import org.pjsip.pjsua2.pj_constants_;
import org.pjsip.pjsua2.pj_ssl_cert_verify_flag_t;
import org.pjsip.pjsua2.pjsua_ip_change_op;

import java.util.ArrayList;

public class SipEndpoint extends Endpoint {
    private final SipService service;
    @SuppressWarnings("FieldCanBeLocal")
    private final String TAG = "SipEndpoint";

    public SipEndpoint(SipService service) {
        super();
        this.service = service;
    }

    @Override
    public void onTransportState(OnTransportStateParam prm) {
        super.onTransportState(prm);

        if (service.getSharedPreferencesHelper().isVerifySipServerCert() &&
                prm.getType().equalsIgnoreCase("TLS")
        ) {
            long verifyStatus = prm.getTlsInfo().getVerifyStatus();
            int success = pj_ssl_cert_verify_flag_t.PJ_SSL_CERT_ESUCCESS;
            int identityMismatch = pj_ssl_cert_verify_flag_t.PJ_SSL_CERT_EIDENTITY_NOT_MATCH;

            boolean verified = verifyStatus == success;
            if (verifyStatus == identityMismatch) {
                // Older PJSIP/OpenSSL combinations can report an identity mismatch for a valid
                // single-label wildcard. Only allow that one isolated error, and only after the
                // native chain verification has produced no other failure bits.
                ArrayList<String> certNames = getCertNames(prm);
                for (SipAccount account : SipService.getActiveSipAccounts().values()) {
                    String host = account.getData().getHost();
                    if (host != null && SipTlsUtils.isWildcardValid(certNames, host)) {
                        verified = true;
                        break;
                    }
                }
            }

            if (!verified) {
                Logger.error(TAG, "SIP TLS peer verification failed with status " + verifyStatus);
                service.getBroadcastEmitter().notifyTlsVerifyStatusFailed();
                // Fail closed. Do not keep a registration or call alive on an untrusted transport.
                service.stopSelf();
            } else {
                Logger.info(TAG, "SIP TLS peer verification succeeded");
            }
        }
    }

    @Override
    public void onIpChangeProgress(OnIpChangeProgressParam prm) {
        super.onIpChangeProgress(prm);
        if (prm.getStatus() != pj_constants_.PJ_SUCCESS) {
            hangupAllCalls();
            service.getBroadcastEmitter().callReconnectionState(CallReconnectionState.FAILED);
            return;
        }

        if (prm.getOp() == pjsua_ip_change_op.PJSUA_IP_CHANGE_OP_COMPLETED) {
            service.getBroadcastEmitter().callReconnectionState(CallReconnectionState.SUCCESS);
        }
    }

    private ArrayList<String> getCertNames(OnTransportStateParam prm) {
        ArrayList<String> certNames = new ArrayList<>();
        certNames.add(prm.getTlsInfo().getRemoteCertInfo().getSubjectCn());
        for (SslCertName name : prm.getTlsInfo().getRemoteCertInfo().getSubjectAltName()) {
            certNames.add(name.getName());
        }
        return certNames;
    }

}
