package kz.gonets;

import kz.gov.pki.kalkan.jce.provider.KalkanProvider;
import kz.gov.pki.kalkan.xmldsig.KncaXS;
import org.apache.ws.security.message.token.SecurityTokenReference;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Транспортная подпись SOAP-запроса для ШЭП: WS-Security, подписывается SOAP Body.
 * ГОСТ 34.10-2015 (512) + ГОСТ 34.11-2015 (512), exclusive C14N,
 * сертификат в KeyInfo/SecurityTokenReference, ключ из PKCS#12 через Калкан.
 */
public final class Signer {
    private Signer() {}

    /** Настройки подписи окружения (переменные уже подставлены). */
    public static class Cfg {
        public String p12 = "";
        public String password = "";
        public String alias = "";
    }

    private static final String ALGORITHM_DIGEST = "urn:ietf:params:xml:ns:pkigovkz:xmlsec:algorithms:gostr34112015-512";
    private static final String ALGORITHM_SIGNATURE = "urn:ietf:params:xml:ns:pkigovkz:xmlsec:algorithms:gostr34102015-gostr34112015-512";
    private static final String WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    private static boolean initialized;

    private static synchronized void init() {
        if (initialized) return;
        try {
            Security.removeProvider(KalkanProvider.PROVIDER_NAME);
            Security.addProvider(new KalkanProvider());
            KncaXS.loadXMLSecurity();
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("Библиотеки Калкан не найдены рядом с приложением (" + e.getMessage() + ")");
        }
        initialized = true;
    }

    private record Key(PrivateKey key, X509Certificate cert, String alias) {}

    private static Key open(Cfg cfg) throws Exception {
        if (cfg.p12 == null || cfg.p12.isBlank()) throw new IllegalArgumentException("Не указан файл ключа (.p12)");
        File f = new File(cfg.p12.trim());
        if (!f.isFile()) throw new IllegalArgumentException("Файл ключа не найден: " + cfg.p12);
        init();
        char[] pass = (cfg.password == null ? "" : cfg.password).toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12", KalkanProvider.PROVIDER_NAME);
        try (InputStream is = new FileInputStream(f)) {
            store.load(is, pass);
        }
        String useAlias = cfg.alias == null ? "" : cfg.alias.trim();
        if (useAlias.isEmpty()) { // берём последний алиас хранилища
            Enumeration<String> als = store.aliases();
            while (als.hasMoreElements()) useAlias = als.nextElement();
        }
        if (useAlias.isEmpty()) throw new IllegalStateException("в файле ключа нет записей");
        PrivateKey key = (PrivateKey) store.getKey(useAlias, pass);
        X509Certificate cert = (X509Certificate) store.getCertificate(useAlias);
        if (key == null || cert == null) throw new IllegalStateException("не найден ключ или сертификат с алиасом «" + useAlias + "»");
        return new Key(key, cert, useAlias);
    }

    /** Понятное сообщение об ошибке подписи. */
    private static IllegalStateException fail(Throwable e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && (e.getMessage().startsWith("Не указан") || e.getMessage().startsWith("Файл ключа"))) {
            return new IllegalStateException(e.getMessage());
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (root != e) msg += " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        if (msg.toLowerCase().contains("mac invalid") || msg.toLowerCase().contains("wrong password")) {
            msg = "Неверный пароль к файлу ключа или файл повреждён";
        } else if (msg.contains("SAXParseException")) {
            String m = root.getMessage() == null ? msg : root.getMessage();
            msg = "Тело запроса не является корректным XML: " + m;
        } else if (e instanceof IllegalStateException && e.getMessage() != null && root == e) {
            msg = e.getMessage();
        }
        return new IllegalStateException(msg);
    }

    /** Сведения о сертификате: subject, issuer, serial, notBefore, notAfter, sigAlg, alias. */
    public static synchronized Map<String, String> info(Cfg cfg) {
        try {
            Key k = open(cfg);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("subject", k.cert.getSubjectX500Principal().toString());
            m.put("issuer", k.cert.getIssuerX500Principal().toString());
            m.put("serial", k.cert.getSerialNumber().toString(16));
            m.put("notBefore", k.cert.getNotBefore().toInstant().toString());
            m.put("notAfter", k.cert.getNotAfter().toInstant().toString());
            m.put("sigAlg", k.cert.getSigAlgName());
            m.put("alias", k.alias);
            return m;
        } catch (Throwable e) {
            throw fail(e);
        }
    }

    /** Подписанный XML (без отправки). */
    public static synchronized String sign(Cfg cfg, String xml) {
        try {
            Key k = open(cfg);
            return sign(xml, k.key, k.cert);
        } catch (Throwable e) {
            throw fail(e);
        }
    }

    /** Подпись тела перед отправкой. Токен и переменные уже подставлены, подписывается ровно то, что уйдёт. */
    static Http.Spec withSignature(Http.Spec spec) {
        if (spec.sign == null || spec.body == null || spec.body.isEmpty()) return spec;
        try {
            Http.Spec out = spec.copy();
            out.body = sign(spec.sign, spec.body);
            return out;
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Подпись: " + e.getMessage());
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && ns.equals(c.getNamespaceURI()) && local.equals(c.getLocalName())) return (Element) c;
        }
        return null;
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    static String sign(String xml, PrivateKey key, X509Certificate cert) throws Exception {
        Document doc = parse(xml);
        Element env = doc.getDocumentElement();
        String soapNs = env.getNamespaceURI();
        if (soapNs == null || !"Envelope".equals(env.getLocalName())) throw new IllegalArgumentException("корневой элемент должен быть SOAP Envelope");
        Element body = child(env, soapNs, "Body");
        if (body == null) throw new IllegalArgumentException("в запросе нет SOAP Body");
        Element header = child(env, soapNs, "Header");
        if (header == null) {
            String p = env.getPrefix();
            header = doc.createElementNS(soapNs, (p == null || p.isEmpty()) ? "Header" : p + ":Header");
            env.insertBefore(header, body);
        }

        // Заголовок безопасности
        Element security = doc.createElementNS(WSSE_NS, "wsse:Security");
        security.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsse", WSSE_NS);
        header.appendChild(security);
        String prefix = "wsu";
        for (int i = 0; i < header.getAttributes().getLength(); i++) { // если пространство wsu уже объявлено у заголовка, берём его префикс
            Attr a = (Attr) header.getAttributes().item(i);
            if (WSU_NS.equals(a.getNamespaceURI())) prefix = a.getName();
        }
        security.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, WSU_NS);
        String bodyId = "id-" + UUID.randomUUID();
        // Объявление wsu должно стоять на самом Body (как делает SAAJ): иначе при сериализации оно появится там
        // само, и каноническая форма тела, а с ней и хеш, будут не такими, как при подписании.
        body.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, WSU_NS);
        body.setAttributeNS(WSU_NS, prefix + ":Id", bodyId);
        body.setIdAttributeNS(WSU_NS, "Id", true);

        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);
        XMLSignature sig = new XMLSignature(doc, "", ALGORITHM_SIGNATURE, Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        sig.addDocument("#" + bodyId, transforms, ALGORITHM_DIGEST);
        security.appendChild(sig.getElement());

        SecurityTokenReference ref = new SecurityTokenReference(doc);
        ref.setKeyIdentifier(cert);
        sig.getKeyInfo().addUnknownElement(ref.getElement());
        sig.sign(key);

        String signed = serialize(doc);
        verify(signed, cert); // проверяем результат так же, как это сделает получатель
        return signed;
    }

    /** Разбирает готовый XML заново и проверяет подпись тела: поймает, например, потерянные объявления пространств имён. */
    private static void verify(String signedXml, X509Certificate cert) throws Exception {
        Document doc = parse(signedXml);
        Element env = doc.getDocumentElement();
        Element body = child(env, env.getNamespaceURI(), "Body");
        body.setIdAttributeNS(WSU_NS, "Id", true);
        NodeList list = doc.getElementsByTagNameNS(DSIG_NS, "Signature");
        if (list.getLength() != 1) throw new IllegalStateException("в подписанном XML нет подписи");
        XMLSignature sig = new XMLSignature((Element) list.item(0), "");
        if (!sig.checkSignatureValue(cert)) throw new IllegalStateException("созданная подпись не прошла проверку");
    }
}
