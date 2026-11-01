package com.geminiapp.chat;

import org.spongycastle.asn1.ASN1Encoding;
import org.spongycastle.asn1.DERIA5String;
import org.spongycastle.asn1.x509.BasicConstraints;
import org.spongycastle.asn1.x509.Certificate;
import org.spongycastle.asn1.x509.Extension;
import org.spongycastle.asn1.x509.Extensions;
import org.spongycastle.asn1.x509.GeneralName;
import org.spongycastle.asn1.x509.GeneralNames;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.asn1.x509.TBSCertificate;
import org.spongycastle.crypto.Digest;
import org.spongycastle.crypto.digests.SHA1Digest;
import org.spongycastle.crypto.digests.SHA256Digest;
import org.spongycastle.crypto.digests.SHA384Digest;
import org.spongycastle.crypto.digests.SHA512Digest;
import org.spongycastle.crypto.params.AsymmetricKeyParameter;
import org.spongycastle.crypto.Signer;
import org.spongycastle.crypto.signers.DSADigestSigner;
import org.spongycastle.crypto.signers.ECDSASigner;
import org.spongycastle.crypto.signers.RSADigestSigner;
import org.spongycastle.crypto.tls.AlertDescription;
import org.spongycastle.crypto.tls.CertificateRequest;
import org.spongycastle.crypto.tls.CipherSuite;
import org.spongycastle.crypto.tls.DefaultTlsClient;
import org.spongycastle.crypto.tls.NameType;
import org.spongycastle.crypto.tls.ProtocolVersion;
import org.spongycastle.crypto.tls.ServerName;
import org.spongycastle.crypto.tls.ServerNameList;
import org.spongycastle.crypto.tls.TlsAuthentication;
import org.spongycastle.crypto.tls.TlsClientProtocol;
import org.spongycastle.crypto.tls.TlsCredentials;
import org.spongycastle.crypto.tls.TlsExtensionsUtils;
import org.spongycastle.crypto.tls.TlsFatalAlert;
import org.spongycastle.crypto.util.PublicKeyFactory;
import org.spongycastle.util.Arrays;
import org.spongycastle.util.encoders.Base64;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Собственный TLS 1.2 клиент на чистой Java (SpongyCastle, lightweight API).
 * Нужен для Android 1.0 - 4.0.x, где системный TLS не умеет TLS 1.2, и ничего
 * (Wolfius и т.п.) устанавливать не требуется. Не использует системный JSSE вообще.
 *
 * Проверка сертификата сервера: цепочка подписей до вшитого корня (Roots),
 * имя хоста по SAN, срок действия (если часы устройства похожи на настоящие),
 * флаг CA у промежуточных сертификатов.
 */
final class LegacyTls {

    private LegacyTls() {}

    /** 2020-01-01: если часы устройства показывают раньше, срок действия не проверяем. */
    private static final long SANE_CLOCK = 1577836800000L;

    static final class Conn {
        final Socket socket;
        final TlsClientProtocol proto;
        Conn(Socket socket, TlsClientProtocol proto) {
            this.socket = socket;
            this.proto = proto;
        }
        void close() {
            try { proto.close(); } catch (Throwable ignored) {}
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    static Conn connect(String host, int port, int connectTimeout, int readTimeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(readTimeout);
            TlsClientProtocol proto = new TlsClientProtocol(
                socket.getInputStream(), socket.getOutputStream(), new SecureRandom());
            proto.connect(new Client(host));
            return new Conn(socket, proto);
        } catch (IOException e) {
            try { socket.close(); } catch (Throwable ignored) {}
            throw e;
        } catch (RuntimeException e) {
            try { socket.close(); } catch (Throwable ignored) {}
            throw new IOException("TLS: " + e);
        }
    }

    // ---------------------------------------------------------------- TLS клиент

    private static final class Client extends DefaultTlsClient {
        private final String host;

        Client(String host) {
            this.host = host;
        }

        @Override
        public int[] getCipherSuites() {
            // RSA и ECDSA: google.com отдаёт RSA или ECDSA цепочку, github.com - обычно ECDSA
            return new int[] {
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256
            };
        }

        @Override
        public ProtocolVersion getMinimumVersion() {
            return ProtocolVersion.TLSv12;
        }

        @Override
        public Hashtable getClientExtensions() throws IOException {
            Hashtable ext = TlsExtensionsUtils.ensureExtensionsInitialised(super.getClientExtensions());
            Vector names = new Vector();
            names.addElement(new ServerName(NameType.host_name, host));
            TlsExtensionsUtils.addServerNameExtension(ext, new ServerNameList(names));
            return ext;
        }

        @Override
        public TlsAuthentication getAuthentication() throws IOException {
            return new TlsAuthentication() {
                public void notifyServerCertificate(org.spongycastle.crypto.tls.Certificate c)
                        throws IOException {
                    try {
                        verifyChain(c.getCertificateList(), host);
                    } catch (Exception e) {
                        throw new TlsFatalAlert(AlertDescription.bad_certificate, e);
                    }
                }

                public TlsCredentials getClientCredentials(CertificateRequest request) {
                    return null;
                }
            };
        }
    }

    // ------------------------------------------------------- проверка сертификата

    private static void verifyChain(Certificate[] chain, String host) throws Exception {
        if (chain == null || chain.length == 0) throw new Exception("empty certificate chain");

        SubjectPublicKeyInfo[] roots = new SubjectPublicKeyInfo[Roots.DER_BASE64.length];
        for (int i = 0; i < roots.length; i++) {
            roots[i] = Certificate.getInstance(Base64.decode(Roots.DER_BASE64[i])).getSubjectPublicKeyInfo();
        }

        // Ищем в цепочке сертификат, чей ключ совпадает с доверенным корнем
        // (так работают и сам корень, и его кросс-подписанная копия).
        int top = -1;
        for (int i = 0; i < chain.length && top < 0; i++) {
            if (isRootKey(chain[i].getSubjectPublicKeyInfo(), roots) != null) top = i;
        }
        if (top < 0) {
            // Последний сертификат должен быть подписан доверенным корнем
            top = chain.length - 1;
            boolean ok = false;
            for (int i = 0; i < roots.length && !ok; i++) {
                try { verifySignature(chain[top], roots[i]); ok = true; } catch (Exception ignored) {}
            }
            if (!ok) throw new Exception("untrusted certificate chain");
        }

        for (int i = 0; i < top; i++) verifySignature(chain[i], chain[i + 1].getSubjectPublicKeyInfo());

        boolean checkDates = System.currentTimeMillis() > SANE_CLOCK;
        Date now = new Date();
        for (int i = 0; i <= top; i++) {
            TBSCertificate tbs = chain[i].getTBSCertificate();
            if (checkDates && (now.before(tbs.getStartDate().getDate()) || now.after(tbs.getEndDate().getDate()))) {
                throw new Exception("certificate expired or not yet valid");
            }
            if (i > 0) {
                Extensions exts = tbs.getExtensions();
                BasicConstraints bc = exts == null ? null
                    : BasicConstraints.getInstance(exts.getExtensionParsedValue(Extension.basicConstraints));
                if (bc == null || !bc.isCA()) throw new Exception("intermediate is not a CA");
            }
        }

        if (!hostMatches(chain[0], host)) throw new Exception("hostname mismatch: " + host);
    }

    private static SubjectPublicKeyInfo isRootKey(SubjectPublicKeyInfo k, SubjectPublicKeyInfo[] roots)
            throws IOException {
        byte[] enc = k.getEncoded(ASN1Encoding.DER);
        for (int i = 0; i < roots.length; i++) {
            if (Arrays.areEqual(enc, roots[i].getEncoded(ASN1Encoding.DER))) return roots[i];
        }
        return null;
    }

    private static void verifySignature(Certificate cert, SubjectPublicKeyInfo issuerKey) throws Exception {
        String oid = cert.getSignatureAlgorithm().getAlgorithm().getId();
        Digest d;
        boolean ec = oid.startsWith("1.2.840.10045.4.");
        if (oid.equals("1.2.840.113549.1.1.11") || oid.equals("1.2.840.10045.4.3.2")) d = new SHA256Digest();
        else if (oid.equals("1.2.840.113549.1.1.12") || oid.equals("1.2.840.10045.4.3.3")) d = new SHA384Digest();
        else if (oid.equals("1.2.840.113549.1.1.13") || oid.equals("1.2.840.10045.4.3.4")) d = new SHA512Digest();
        else if (oid.equals("1.2.840.113549.1.1.5") || oid.equals("1.2.840.10045.4.1")) d = new SHA1Digest();
        else throw new Exception("unsupported signature algorithm " + oid);

        AsymmetricKeyParameter key = PublicKeyFactory.createKey(issuerKey);
        Signer signer = ec ? (Signer) new DSADigestSigner(new ECDSASigner(), d) : (Signer) new RSADigestSigner(d);
        signer.init(false, key);
        byte[] tbs = cert.getTBSCertificate().getEncoded(ASN1Encoding.DER);
        signer.update(tbs, 0, tbs.length);
        if (!signer.verifySignature(cert.getSignature().getBytes())) {
            throw new Exception("bad certificate signature");
        }
    }

    private static boolean hostMatches(Certificate leaf, String host) throws Exception {
        Extensions exts = leaf.getTBSCertificate().getExtensions();
        if (exts == null) return false;
        Object san = exts.getExtensionParsedValue(Extension.subjectAlternativeName);
        if (san == null) return false;
        GeneralName[] names = GeneralNames.getInstance(san).getNames();
        String h = host.toLowerCase();
        for (int i = 0; i < names.length; i++) {
            if (names[i].getTagNo() != GeneralName.dNSName) continue;
            String p = DERIA5String.getInstance(names[i].getName()).getString().toLowerCase();
            if (p.equals(h)) return true;
            if (p.startsWith("*.")) {
                int dot = h.indexOf('.');
                if (dot > 0 && h.substring(dot).equals(p.substring(1))) return true;
            }
        }
        return false;
    }
}
