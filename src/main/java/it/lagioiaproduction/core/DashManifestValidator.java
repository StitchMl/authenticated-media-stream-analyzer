package it.lagioiaproduction.core;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

final class DashManifestValidator {
    private DashManifestValidator() { }

    static Document parse(String manifest) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            document = builder.parse(new InputSource(new StringReader(manifest)));
        } catch (Exception ex) {
            throw new IllegalStateException("Risposta non valida: il server non ha restituito un manifest DASH XML.", ex);
        }
        if (!"MPD".equals(document.getDocumentElement().getLocalName())) {
            throw new IllegalStateException("Risposta non valida: atteso un manifest DASH, ricevuta un'altra pagina.");
        }
        return document;
    }

    static void validate(String manifest) {
        Document document = parse(manifest);
        var protections = document.getElementsByTagNameNS("*", "ContentProtection");
        for (int i = 0; i < protections.getLength(); i++) {
            var protection = (org.w3c.dom.Element) protections.item(i);
            var encryption = protection.getElementsByTagNameNS("urn:mpeg:dash:schema:sea:2012", "SegmentEncryption");
            var periods = protection.getElementsByTagNameNS("urn:mpeg:dash:schema:sea:2012", "CryptoPeriod");
            if (!"urn:mpeg:dash:sea:2012".equals(protection.getAttribute("schemeIdUri"))
                    || encryption.getLength() != 1 || periods.getLength() != 1
                    || !"urn:mpeg:dash:sea:aes128-cbc:2013".equals(
                            ((org.w3c.dom.Element) encryption.item(0)).getAttribute("schemeIdUri"))) {
                throw new StreamManifestResolver.NonRetryableResolveException(
                        "Cifratura DASH non supportata: sono supportati solo DASH SEA AES-128-CBC con chiave HTTP e IV esplicito.", null);
            }
            var period = (org.w3c.dom.Element) periods.item(0);
            var systems = protection.getElementsByTagNameNS("urn:mpeg:dash:schema:sea:2012", "KeySystem");
            if (systems.getLength() != 1 || !"urn:mpeg:dash:sea:keysys:http:2013".equals(
                    ((org.w3c.dom.Element) systems.item(0)).getAttribute("keySystemUri"))
                    || !period.getAttribute("IV").matches("0[xX][0-9a-fA-F]{32}")
                    || period.getAttribute("keyUriTemplate").isBlank()
                    || period.getAttribute("keyUriTemplate").contains("$")
                    || period.hasAttribute("numSegments") || period.hasAttribute("offset")) {
                throw new StreamManifestResolver.NonRetryableResolveException(
                        "Configurazione DASH SEA non supportata: chiave variabile o IV non valido.", null);
            }
        }
    }
}
