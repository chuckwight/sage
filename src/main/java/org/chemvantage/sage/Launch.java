package org.chemvantage.sage;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/launch")
public class Launch extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		/*
		 *  To access this servlet, the request MUST contain either an email
		 *  address or a token.
		 *  1) Email: This method compares the hashed email to the Cookie "hashedId". 
		 *     If matched, transfers control to Sage servlet.
		 *     Otherwise, sends a tokenized login link to the email address.
		 *  2) Token: This method validates the token, sets a Cookie and transfers to Sage.
		 *  
		 *  Failed: send error message and provide link to index.html
		 */
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {
			String token = request.getParameter("Token");
			String email = request.getParameter("Email");
			if (token != null) {
				String hashedId = validateToken(token);
				Cookie c = new Cookie("hashedId",hashedId);
				response.addCookie(c);
				out.println(Sage.start(hashedId));
			} else if (email != null) {
				String hashedId = getHash(email);
				Cookie[] cookies = request.getCookies();
				for (Cookie c : cookies) {
					if ("hashedId".equals(c.getName()) && c.getValue().equals(hashedId)) {
						out.println(Sage.start(hashedId));
						return;
					}
				}
				Util.sendEmail(null,email,"Sage Login Link", tokenMessage(createToken(hashedId)));
				out.println(emailSent());
			}
		} catch (Exception e) {
			
		}
	}
	
	static String getHash(String email) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
        	byte[] bytes = md.digest((email + Util.getSalt()).getBytes(StandardCharsets.UTF_8));
        	StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
		} catch (Exception e) {
        	return null;
        }
	}
	
	static String tokenMessage(String token) {
		DecodedJWT decoded = JWT.decode(token);
		Date exp = decoded.getExpiresAt();
		String iss = decoded.getIssuer();
		return Util.head 
				+ "<h1>Login to Sage</h1>"
				+ "Please use the tokenized link below to login to your Sage account. "
				+ "The link will expire in 5 minutes at " + exp + ".<p>"
				+ "<a href='" + iss + "/launch?Token=" + token + "'>" + iss + "/launch?Token=" + token + "</a>"
				+ Util.foot;
	}
	
	static String createToken(String hashedId) throws Exception {
		Date now = new Date();
		Date exp = new Date(now.getTime() + 360000L);  // 5 minutes from now
		String nonce = Nonce.generateNonce();
		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		
		String token = JWT.create()
				.withIssuer(Util.serverUrl)
				.withSubject(hashedId)
				.withExpiresAt(exp)
				.withIssuedAt(now)
				.withClaim("nonce", nonce)
				.sign(algorithm);
		
		return token;
	}
	
	static String validateToken(String token) throws Exception {
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			JWTVerifier verifier = JWT.require(algorithm).withIssuer(Util.serverUrl).build();
			verifier.verify(token);
			DecodedJWT decoded = JWT.decode(token);
			String nonce = decoded.getClaim("nonce").asString();
			if (!Nonce.isUnique(nonce)) throw new Exception("The login link can onloy be used once.");
			return decoded.getSubject();
	}
	
	static String emailSent() {
		Date now = new Date();
		Date fiveMinutesFromNow = new Date(now.getTime() + 360000L);
		return Util.head 
				+ "<h1>Sage Login Link</h1>"
				+ "We sent an email to yur address containing a tokenized link to login to Sage.<p>"
				+ "The link expires in 5 minutes at " + fiveMinutesFromNow + "."
				+ Util.foot;
	}
}