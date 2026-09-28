package it.lagioiaproduction.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URI;
import java.util.HexFormat;
public class SeaStreamDownloaderTest {
 private static final String PROTECTION = "<ContentProtection schemeIdUri='urn:mpeg:dash:sea:2012'><sea:SegmentEncryption schemeIdUri='urn:mpeg:dash:sea:aes128-cbc:2013'/><sea:KeySystem keySystemUri='urn:mpeg:dash:sea:keysys:http:2013'/><sea:CryptoPeriod IV='0x00000000000000000000000000000000' keyUriTemplate='https://example.org/key'/></ContentProtection>";
 private static String manifest(String timeline) {
  return "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:sea='urn:mpeg:dash:schema:sea:2012' type='static'><BaseURL>https://example.org/media/</BaseURL><Period><AdaptationSet contentType='video'>"+PROTECTION+"<SegmentTemplate initialization='init-$RepresentationID$' media='seg-$Time$-$Number$?signature=keep' startNumber='7'><SegmentTimeline>"+timeline+"</SegmentTimeline></SegmentTemplate><Representation id='low' bandwidth='10'/><Representation id='high' bandwidth='20'/></AdaptationSet></Period></MPD>";
 }
 @Test public void timelineAndRepresentationAndSignedQuery() {
  var tracks=SeaStreamDownloader.parse(manifest("<S t='12' d='6' r='1'/><S t='40' d='4'/><S d='5'/>"),URI.create("https://example.org/manifest"));
  var t=tracks.get(0);
  assertEquals("https://example.org/media/init-high",t.initialization());
  assertEquals(java.util.List.of("https://example.org/media/seg-12-7?signature=keep","https://example.org/media/seg-18-8?signature=keep","https://example.org/media/seg-40-9?signature=keep","https://example.org/media/seg-44-10?signature=keep"),t.segments());
  assertEquals(16,t.iv().length);
 }
 @Test public void decryptKnownAesCbcVectorWithPadding() throws Exception {
  // NIST SP 800-38A AES-CBC first block, followed by an independently generated PKCS#7 padding block.
  var hex=HexFormat.of();
  byte[] key=hex.parseHex("2b7e151628aed2a6abf7158809cf4f3c");
  byte[] iv=hex.parseHex("000102030405060708090a0b0c0d0e0f");
  javax.crypto.Cipher encrypt=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
  encrypt.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.IvParameterSpec(iv));
  byte[] plain=hex.parseHex("6bc1bee22e409f96e93d7e117393172a");
  byte[] encrypted=encrypt.doFinal(plain);
  assertEquals("7649abac8119b246cee98e9b12e9197d",hex.formatHex(java.util.Arrays.copyOf(encrypted,16)));
  assertArrayEquals(plain,SeaStreamDownloader.decrypt(encrypted,key,iv));
  assertArrayEquals(plain,SeaStreamDownloader.decrypt(encrypted,key,iv));
 }
 @Test public void handlesClearAndSharePointEncryptedInitialization() throws Exception {
  byte[] plain=HexFormat.of().parseHex("0000000866747970000000086d6f6f76");
  byte[] key=new byte[16],iv=new byte[16];
  assertArrayEquals(plain,SeaStreamDownloader.decodeInitialization(plain,key,iv));
  var cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
  cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.IvParameterSpec(iv));
  assertArrayEquals(plain,SeaStreamDownloader.decodeInitialization(cipher.doFinal(plain),key,iv));
  assertThrows(IllegalStateException.class,()->SeaStreamDownloader.decodeInitialization(new byte[7],key,iv));
 }
 @Test public void refusesUnsupportedProtectionAndUnboundedTimeline() {
  assertThrows(IllegalStateException.class,()->SeaStreamDownloader.parse(manifest("<S d='6' r='-1'/>"),URI.create("https://example.org/manifest")));
  assertThrows(IllegalStateException.class,()->DashManifestValidator.validate(manifest("<S d='6'/>").replace("aes128-cbc:2013","aes128-gcm:2013")));
  assertThrows(IllegalStateException.class,()->DashManifestValidator.validate(manifest("<S d='6'/>").replace("https://example.org/key","https://example.org/$Number$")));
  assertThrows(IllegalStateException.class,()->DashManifestValidator.parse("<!DOCTYPE MPD SYSTEM 'file:///invalid'><MPD/>"));
 }
 @Test public void rejectsMalformedCiphertextAndMasksCredentials() {
  assertThrows(IllegalStateException.class,()->SeaStreamDownloader.decrypt(new byte[7],new byte[16],new byte[16]));
  assertFalse(FfmpegRunner.redactLog("https://example.org/key?secret=xyz x-spopactoken: hidden").contains("xyz"));
  assertFalse(FfmpegRunner.redactLog("x-spopactoken: hidden").contains("hidden"));
 }
}
