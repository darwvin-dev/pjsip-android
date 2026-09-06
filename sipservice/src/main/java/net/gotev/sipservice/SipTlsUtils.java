package net.gotev.sipservice;

import android.content.Context;
import android.util.Base64;

import org.pjsip.pjsua2.TlsConfig;
import org.pjsip.pjsua2.TransportConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;

/**
 * TLS helpers for the PJSIP transport.
 */
public class SipTlsUtils {

    public static final String TAG = "Endpoint";
    private static final String CA_FILE_NAME = "ca-bundle.crt";

    public static boolean isWildcardValid(ArrayList<String> certNames, String host) {
        Logger.info(TAG, "Trying to verify if wildcard certificate is valid");

        for (String name: certNames) {
            if (name == null || name.isEmpty()) continue;
            Logger.debug(TAG, "Validating certificate name against SIP host");
            if (SipTlsUtils.isWildCardCertValid(name, host.split("\\."))) return true;
        }
        return false;
    }

    /**
     * RFC 6125-style single-label wildcard matching: *.example.com can match
     * sip.example.com, but never a.b.example.com. Partial-label wildcards are rejected.
     */
    private static boolean isWildCardCertValid(String certName, String[] hostTokens) {
        if (certName == null) return false;
        String normalized = certName.trim().toLowerCase();
        String[] certTokens = normalized.split("\\.");
        if (certTokens.length != hostTokens.length) return false;

        for (int i = 0; i < hostTokens.length; i++) {
            String hostToken = hostTokens[i].toLowerCase();
            String certToken = certTokens[i];
            if ("*".equals(certToken) && i == 0) continue;
            if (certToken.contains("*") || !hostToken.equals(certToken)) return false;
        }
        return true;
    }

    /**
     * Configure peer verification with Android's current CA store. This avoids shipping a stale
     * static CA bundle and makes SIP TLS follow the same trust anchors the device is using.
     *
     * A legacy ca-bundle.crt asset is used only as a fallback when AndroidCAStore cannot be read.
     * Verification itself is always enabled when requested; failure to load trust anchors therefore
     * fails closed instead of silently turning verification off.
     */
    public static void setTlsConfig(
            Context context,
            boolean verifyEnabled,
            TransportConfig tlsTransport
    ) {
        if (!verifyEnabled) return;

        TlsConfig tlsConfig = tlsTransport.getTlsConfig();
        try {
            String caBundle = androidCaBundle();
            if (caBundle.isEmpty()) {
                caBundle = assetCaBundle(context);
            }
            if (!caBundle.isEmpty()) {
                tlsConfig.setCaBuf(caBundle);
            } else {
                Logger.error(TAG, "No TLS trust anchors are available; verification will fail closed");
            }

            // Ask the native TLS stack to validate the certificate chain and peer identity.
            // SipEndpoint additionally inspects verifyStatus and rejects every non-success state.
            tlsConfig.setVerifyServer(true);
            tlsTransport.setTlsConfig(tlsConfig);
        } catch (Exception error) {
            Logger.error(TAG, "Unable to configure SIP TLS verification; keeping verifyServer enabled", error);
            try {
                tlsConfig.setVerifyServer(true);
                tlsTransport.setTlsConfig(tlsConfig);
            } catch (Exception ignored) { }
        }
    }

    private static String androidCaBundle() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidCAStore");
        store.load(null);

        StringBuilder pem = new StringBuilder();
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate == null) continue;
            appendPem(pem, certificate.getEncoded());
        }
        return pem.toString();
    }

    private static void appendPem(StringBuilder out, byte[] der) {
        String encoded = Base64.encodeToString(der, Base64.NO_WRAP);
        out.append("-----BEGIN CERTIFICATE-----\n");
        for (int offset = 0; offset < encoded.length(); offset += 64) {
            out.append(encoded, offset, Math.min(offset + 64, encoded.length())).append('\n');
        }
        out.append("-----END CERTIFICATE-----\n");
    }

    private static String assetCaBundle(Context context) {
        try (InputStream input = context.getAssets().open(CA_FILE_NAME);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } catch (IOException ignored) {
            return "";
        }
    }
}
