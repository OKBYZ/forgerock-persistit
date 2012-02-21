/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit.bug;

import com.persistit.Exchange;
import com.persistit.Transaction;
import com.persistit.unit.PersistitUnitTestCase;
import com.persistit.util.Debug;

public class Bug937877Test extends PersistitUnitTestCase {

    /**
     * Test for a race condition that probably caused 937877. This test depends
     * on strategic placement of {@link Debug#await} and {@link Debug#awaken}
     * statements in the Transaction class. If those statements are moved or
     * modified, this test will probably need to be changed.
     * 
     * @throws Exception
     */
    public void testInCommitRaceCondition() throws Exception {

        Debug.enableInterleave();

        final Exchange ex = _persistit.getExchange("persistit", "test", true);
        final Transaction txn = ex.getTransaction();
        txn.begin();
        ex.getValue().put(RED_FOX);
        for (int k = 1; k < 10; k++) {
            ex.clear().append(k).store();
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1000);
                    _persistit.checkpoint();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        txn.commit();
        txn.end();
        String sequence = Debug.sequence();
        Debug.disableInterleave();
        assertTrue(sequence.startsWith("acquire:flushOnCheckpoint_a,release:flushOnCheckpoint_a,"
                + "acquire:flushOnCheckpoint_b,release:flushOnCheckpoint_b,"));
    }
}