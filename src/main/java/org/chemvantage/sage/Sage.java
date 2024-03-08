package org.chemvantage.sage;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

@WebServlet("/sage")
public class Sage extends HttpServlet {

	private static final long serialVersionUID = 1L;

	static String start(String hashedId) {
		return Util.head + "<h1>Welcome to Sage</h1>" + Util.foot;
	}
}
