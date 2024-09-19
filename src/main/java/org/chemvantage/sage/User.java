/*  ChemVantage - A Java web application for online learning
 *   Copyright (C) 2011 ChemVantage LLC
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
import java.util.concurrent.TimeUnit;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;

@Entity
public class User {
	@Id		String hashedId;
			int tokens;
			Date tokensUpdated;
			Long conceptId;
	
		User() {}
		
		User(String hashedId) {
			this.hashedId = hashedId;
			this.tokens = 100; // 100 free tokens to start
			this.tokensUpdated = new Date();
		}
		
		void updateConceptId(Long conceptId) {
			if (!conceptId.equals(this.conceptId)) {
				this.conceptId = conceptId;
				ofy().save().entity(this).now();
			}
		}
		
		void addTokens(int nTokens) {
			this.tokens = tokensRemaining() + nTokens;
			this.tokensUpdated = new Date();
			ofy().save().entity(this).now();
		}
		
		int tokensRemaining() {
			Date now = new Date();
			int hoursSinceUpdate = (int)TimeUnit.HOURS.convert(now.getTime() - tokensUpdated.getTime(),TimeUnit.MILLISECONDS);
			int tokens = this.tokens - hoursSinceUpdate;
			return tokens < 0?0:tokens;
		}
		
		boolean expired() {
			return tokensRemaining() == 0;
		}
}