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

import static com.googlecode.objectify.ObjectifyService.key;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

@Entity
public class Score {    // this object represents a best score achieved by a user on a quiz or homework
	@Parent Key<User> owner;
	@Id	Long conceptId;      // from the datastore.
	@Index	int score = 0;
	Long nextQuestionId = null;
	
	Score() {}
	
	Score(String hashedId, Long conceptId) {
		this.owner = key(User.class,hashedId);
		this.conceptId = conceptId;
	}
}