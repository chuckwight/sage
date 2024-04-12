/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2012 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.util.Date;
import java.util.List;
import java.util.Random;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Nonce {
	@Id String id;
	@Index	Date created;
			
	Nonce() {}
	
	Nonce(String id) {
		this.id = id;
		this.created = new Date();
	}
	
	static String getHexString() {
		Random random =  new Random(new Date().getTime());
        long r1 = random.nextLong();
        long r2 = random.nextLong();
        String hash1 = Long.toHexString(r1);
        String hash2 = Long.toHexString(r2);
        return hash1 + hash2;
	}

	static boolean isUnique(String nonce) {
		boolean unique = false;
		Date now = new Date();
		Nonce n =ofy().load().type(Nonce.class).id(nonce).now();
		if (n==null) {  // nonce has not been used before
			n = new Nonce(nonce);
			unique = true;
		} else {
			// Delete all the old Nonce entities
			Date oneMonthAgo = new Date(now.getTime() - 2592000000L);
			List<Key<Nonce>> expired = ofy().load().type(Nonce.class).filter("created <",oneMonthAgo).keys().list();
			if (expired.size() > 0) ofy().delete().keys(expired);
		}
		ofy().save().entity(n);  // save the Nonce entity to prevent future playbacks
		return unique;
	}
}