package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.cloud.ServiceOptions;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;

@Entity
public class Util {
	@Id Long id;
	private String HMAC256Secret = "ChangeMe";
	private String reCaptchaSecret = "ChangeMe";
	private String reCaptchaSiteKey = "ChangeMe";
	private String openai_key = "ChangeMe";
	private String salt = "ChangeMe";
	private String announcement = "";
	private String sendGridAPIKey = "ChangeMe";
	
	private static Util u;
	@Ignore static String projectId = ServiceOptions.getDefaultProjectId();
	@Ignore static String serverUrl = "https://" + projectId + ".appspot.com";

	private Util() {}
	
	static public String getHMAC256Secret() {if (u==null) init(); return u.HMAC256Secret;}
	static public String getReCaptchaSecret() {if (u==null) init(); return u.reCaptchaSecret;}
	static public String getReCaptchaSiteKeyt() {if (u==null) init(); return u.reCaptchaSiteKey;}
	static public String getOpenAIKey() {if (u==null) init(); return u.openai_key;}
	static public String getSalt() {if (u==null) init(); return u.salt;}
	static public String getAnnouncement() {if (u==null) init(); return u.announcement;}
	static public String getSendGridAPIKey() {if (u==null) init(); return u.sendGridAPIKey;}
	
	static private void init() {
		if (u==null) {
			try {
				u = ofy().load().type(Util.class).id(1L).safe();
			} catch(Exception e) {
				u = new Util();
				ofy().save().entity(u).now();
			}
		}
	}
	
	static String getHash(String email) {
		try {
			if (u==null) init();
			MessageDigest md = MessageDigest.getInstance("SHA-256");
        	byte[] bytes = md.digest((email + u.salt).getBytes(StandardCharsets.UTF_8));
        	StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
		} catch (Exception e) {
        	return null;
        }
	}
	
	static String validateToken(String token) throws Exception {
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			JWTVerifier verifier = JWT.require(algorithm).withIssuer(serverUrl).build();
			verifier.verify(token);
			DecodedJWT decoded = JWT.decode(token);
			String nonce = decoded.getClaim("nonce").asString();
			if (!Nonce.isUnique(nonce)) throw new Exception("The login link can onloy be used once.");
			return decoded.getSubject();
	}
}
