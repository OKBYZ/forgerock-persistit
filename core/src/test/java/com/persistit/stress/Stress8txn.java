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

package com.persistit.stress;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.TestShim;
import com.persistit.Transaction;
import com.persistit.TransactionRunnable;
import com.persistit.Value.Version;
import com.persistit.exception.PersistitException;
import com.persistit.test.TestResult;
import com.persistit.util.ArgParser;
import com.persistit.util.Debug;

/**
 * @version 1.0
 */
public class Stress8txn extends StressBase {
    private final static String SHORT_DESCRIPTION = "Tests transactions";

    private final static String LONG_DESCRIPTION = "   Tests transactions to ensure isolation, atomicity and\r\n"
            + "   consistency.  Each transaction performs several updates\r\n"
            + "   simulating moving cash between accounts.  To exercise\r\n"
            + "   optimistic concurrency control, several threads should run\r\n"
            + "   this test simultaneously.  At the beginning of the run, and\r\n"
            + "   periodically, this class tests whether all 'accounts' are\r\n" + "   consistent";

    @Override
    public String shortDescription() {
        return SHORT_DESCRIPTION;
    }

    @Override
    public String longDescription() {
        return LONG_DESCRIPTION;
    }

    private final static String[] ARGS_TEMPLATE = { "repeat|int:1:0:1000000000|Repetitions",
            "count|int:100:0:100000|Number of iterations per cycle",
            "size|int:1000:1:100000000|Number of 'C' accounts", "seed|int:1:1:20000|Random seed", };

    static boolean _consistencyCheckDone;
    int _size;
    int _seed;
    
    int _mvvReports;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        _ap = new ArgParser("com.persistit.Stress8txn", _args, ARGS_TEMPLATE);
        _total = _ap.getIntValue("count");
        _repeatTotal = _ap.getIntValue("repeat");
        _size = _ap.getIntValue("size");
        _seed = _ap.getIntValue("seed");
        seed(_seed);
        _dotGranularity = 1000;

        try {
            _exs = getPersistit().getExchange("persistit", "shared", true);
        } catch (final Exception ex) {
            handleThrowable(ex);
        }
    }

    /**
     * <p>
     * Implements tests with "accounts" to be updated transactionally There is a
     * hierarchy of accounts categories A, B and C. A accounts contain B
     * accounts which contain C accounts. At all times, the sums of C accounts
     * must match the total in their containing B account, and so on. The
     * overall sum of every account must always be 0. Operations are:
     * <ol>
     * <li>"transfer" (add/subtract) an amount from a C account to another C
     * account within the same B.</li>
     * <li>"transfer" (add/subtract) an amount from a C account to a C account
     * in a different B account, resulting in changes to B and possibly A
     * account totals.</li>
     * <li>Consistency check - determining that the sub-accounts total to the
     * containing account total.</li>
     * </ol>
     * </p>
     * <p>
     * As a wrinkle, a few "account" totals are represented by strings of a
     * length that represents the account total, rather than by an integer. This
     * is to test long record management during transactions.
     * </p>
     * <p>
     * The expected result is that each consistency check will match, no matter
     * what. This includes the result of abruptly stopping and restarting the
     * JVM. The first thread starting this test performs a consistency check
     * across the entire database to make sure that the result of any recovery
     * operation is correct.
     * </p>
     */
    @Override
    public void executeTest() {
        synchronized (Stress8txn.class) {
            if (!_consistencyCheckDone) {
                _consistencyCheckDone = true;
                try {
                    if (totalConsistencyCheck()) {
                        println("Consistency check completed successfully");
                    }
                } catch (final PersistitException pe) {
                    _result = new TestResult(false, pe);
                    forceStop();
                }
            }
        }

        final Transaction txn = _exs.getTransaction();
        final Operation[] ops = new Operation[6];
        ops[0] = new Operation0();
        ops[1] = new Operation1();
        ops[2] = new Operation2();
        ops[3] = new Operation3();
        ops[4] = new Operation4();
        ops[5] = new Operation5();

        for (_repeat = 0; (_repeat < _repeatTotal) && !isStopped(); _repeat++) {
            verboseln();
            verboseln();
            verboseln("Starting test cycle " + _repeat + " at " + tsString());

            for (_count = 0; (_count < _total) && !isStopped(); _count++) {
                try {
                    dot();
                    final int selector = select();
                    final Operation op = ops[selector];
                    final int acct1 = random(0, _size);
                    final int acct2 = random(0, _size);
                    op.setup(acct1, acct2);
                    final int passes = txn.run(op, 100, 5, false);
                    if (passes > 10) {
                        verboseln("pass count=" + passes);
                    }
                    Debug.$assert1.t(passes <= 90);
                    if (op._result != null) {
                        _result = op._result;
                        Debug.$assert1.t(false);
                        forceStop();
                    }
                } catch (final Exception pe) {
                    _result = new TestResult(false, pe);
                    forceStop();
                }
            }
        }

        try {
            _exs.clear().append("stress8txn");
            while (_exs.next(true)) {
                if ((_exs.getValue().isType(String.class)) && (getAccountValue(_exs) > 8000)) {
                    // System.out.println("len=" + getAccountValue(_exs) +
                    // " Key=" + _exs.getKey().toString());
                }
            }
        } catch (final PersistitException pe) {
            _result = new TestResult(false, pe);
            forceStop();
        }
    }

    private int select() {
        final int r = random(0, 1000);
        if (r < 500) {
            return 0;
        }
        if (r < 800) {
            return 1;
        }
        if (r < 900) {
            return  2;
        }
        if (r < 950) {
            return 3;
        }
        if (r < 990) {
            return 4;
        }
        return 5;
    }

    private abstract class Operation implements TransactionRunnable {
        int _a1, _b1, _c1, _a2, _b2, _c2;

        void setup(final int acct1, final int acct2) {
            _a1 = (acct1 / 25);
            _b1 = (acct1 / 5) % 5;
            _c1 = (acct1 % 5);

            _a2 = (acct2 / 25);
            _b2 = (acct2 / 5) % 5;
            _c2 = (acct2 % 5);
        }

        TestResult _result = null;
    }

    private class Operation0 extends Operation {
        /**
         * Transfers from one C account to another within the same B
         */
        @Override
        public void runTransaction() throws PersistitException {
            final int delta = random(-1000, 1000);
            if (_c1 != _c2) {
                _exs.clear().append("stress8txn").append(_a1).append(_b1).append(_c1).fetch();
                putAccountValue(_exs, getAccountValue(_exs) + delta, _c1 == 1);
                _exs.store();

                _exs.clear().append("stress8txn").append(_a1).append(_b1).append(_c2).fetch();
                putAccountValue(_exs, getAccountValue(_exs) - delta, _c2 == 1);
                _exs.store();
            }
        }
    }

    private class Operation1 extends Operation {
        /*
         * Transfers from one C account to another in possibly a different B
         * account
         */
        @Override
        public void runTransaction() throws PersistitException {
            final int delta = random(-1000, 1000);
            if ((_c1 != _c2) || (_b1 != _b2) || (_a1 != _a2)) {
                _exs.clear().append("stress8txn").append(_a1).append(_b1).append(_c1).fetch();
                putAccountValue(_exs, getAccountValue(_exs) + delta, _c1 == 1);
                _exs.store();

                _exs.clear().append("stress8txn").append(_a2).append(_b2).append(_c2).fetch();
                putAccountValue(_exs, getAccountValue(_exs) - delta, _c2 == 1);
                _exs.store();
            }

            if ((_b1 != _b2) || (_a1 != _a2)) {
                _exs.clear().append("stress8txn").append(_a1).append(_b1).fetch();
                putAccountValue(_exs, getAccountValue(_exs) + delta, _b1 == 1);
                _exs.store();

                _exs.clear().append("stress8txn").append(_a2).append(_b2).fetch();
                putAccountValue(_exs, getAccountValue(_exs) - delta, _b1 == 1);
                _exs.store();
            }

            if (_a1 != _a2) {
                _exs.clear().append("stress8txn").append(_a1).fetch();
                putAccountValue(_exs, getAccountValue(_exs) + delta, _a1 == 1);
                _exs.store();

                _exs.clear().append("stress8txn").append(_a2).fetch();
                putAccountValue(_exs, getAccountValue(_exs) - delta, _a1 == 1);
                _exs.store();
            }
        }
    }

    private class Operation2 extends Operation {
        /**
         * Perform consistency check across a B account
         */
        @Override
        public void runTransaction() throws PersistitException {
            _result = null;
            _exs.clear().append("stress8txn").append(_a1).append(_b1).fetch();
            final int valueB = getAccountValue(_exs);
            final int totalC = accountTotal(_exs);
            if (valueB != totalC) {
                _result = new TestResult(false, "totalC=" + totalC + " valueB=" + valueB + " at " + _exs);
                inconsistent(_result);
                Debug.$assert1.t(false);
            }
        }
    }

    private class Operation3 extends Operation {
        /**
         * Perform consistency check across an A account
         */
        @Override
        public void runTransaction() throws PersistitException {
            _result = null;
            _exs.clear().append("stress8txn").append(_a1).fetch();
            final int valueA = getAccountValue(_exs);
            final int totalB = accountTotal(_exs);
            if (valueA != totalB) {
                _result = new TestResult(false, "totalB=" + totalB + " valueA=" + valueA + " at " + _exs);
                inconsistent(_result);
                Debug.$assert1.t(false);
            }
        }
    }

    private class Operation4 extends Operation {
        /**
         * Perform consistency check across all A accounts
         */
        @Override
        public void runTransaction() throws PersistitException {
            _result = null;
            _exs.clear().append("stress8txn");
            final int totalA = accountTotal(_exs);
            if (totalA != 0) {
                _result = new TestResult(false, "totalA=" + totalA + " at " + _exs);
                inconsistent(_result);
                Debug.$assert1.t(false);
            }
        }
    }


    private class Operation5 extends Operation {
        /**
         * Perform consistency check across an A account
         */
        @Override
        public void runTransaction() throws PersistitException {
            totalConsistencyCheck();
            if ((_mvvReports++ % 1000) == 0) {
                inconsistent(new TestResult(true, "Consistency check passed"));
            }
        }
    }

    private int accountTotal(final Exchange ex) throws PersistitException {
        int total = 0;
        ex.append(Key.BEFORE);
        while (ex.next()) {
            int value = getAccountValue(ex);
            total += value;
        }
        ex.cut();
        return total;
    }

    private boolean totalConsistencyCheck() throws PersistitException {
        int totalA = 0;
        final Exchange exa = new Exchange(_exs);
        final Exchange exb = new Exchange(_exs);
        final Exchange exc = new Exchange(_exs);

        int countA = 0;
        exa.clear().append("stress8txn").append(Key.BEFORE);
        while (exa.next()) {
            countA++;
            exa.fetch();
            final int valueA = getAccountValue(exa);
            final int valueAA = getAccountValue(exa);
            Debug.$assert1.t(valueA == valueAA);
            totalA += valueA;
            int totalB = 0;
            exa.getKey().copyTo(exb.getKey());
            exb.append(Key.BEFORE);
            int countB = 0;
            while (exb.next()) {
                countB++;
                exb.fetch();
                final int valueB = getAccountValue(exb);
                final int valueBB = getAccountValue(exb);
                Debug.$assert1.t(valueB == valueBB);

                totalB += valueB;
                int totalC = 0;
                exb.getKey().copyTo(exc.getKey());
                exc.append(Key.BEFORE);
                int countC = 0;
                while (exc.next()) {
                    countC++;
                    Key key1 = new Key(exc.getKey());
                    final int valueC = getAccountValue(exc);
                    exc.fetch();
                    Key key2 = new Key(exc.getKey());
                    
                    final int valueCC = getAccountValue(exc);

                    Debug.$assert1.t(valueC == valueCC);
                    totalC += valueC;
                }
                if (totalC != valueB) {
                    int totalC1 = 0;
                    int countC1 = 0;
                    while (exc.next()) {
                        countC1++;
                        Key key1 = new Key(exc.getKey());
                        final int valueC1 = getAccountValue(exc);
                        exc.fetch();
                        Key key2 = new Key(exc.getKey());
                        
                        final int valueCC1 = getAccountValue(exc);

                        Debug.$assert1.t(valueC1 == valueCC1);
                        totalC1 += valueC1;
                    }
                    _result = new TestResult(false, "totalC=" + totalC + " valueB=" + valueB + " at " + exb);
                    inconsistent(_result);
                    Debug.$assert1.t(false);
                    forceStop();
                    return false;
                }
            }
            if (totalB != valueA) {
                _result = new TestResult(false, "totalB=" + totalB + " valueA=" + valueA + " at " + exa);
                inconsistent(_result);
                Debug.$assert1.t(false);
                forceStop();
                return false;
            }
        }
        if (totalA != 0) {
            _result = new TestResult(false, "totalA=" + totalA + " at " + exa);
            inconsistent(_result);
            Debug.$assert1.t(false);
            forceStop();
            return false;
        }
        return true;
    }

    private int getAccountValue(final Exchange ex) {
        if (!ex.getValue().isDefined()) {
            return 0;
        }
        try {
            if (ex.getValue().isType(String.class)) {
                ex.getValue().getString(_sb);
                return _sb.length();
            } else {
                return ex.getValue().getInt();
            }
        } catch (final NullPointerException npe) {
            printStackTrace(npe);
            try {
                Thread.sleep(10000);
            } catch (final InterruptedException ie) {
            }
            throw npe;
        }
    }

    private void putAccountValue(final Exchange ex, final int value, final boolean string) {
        if ((value > 0) && (value < 25000) && ((random(0, 100) == 0) || string)) {
            _sb.setLength(0);
            int i = 0;
            for (i = 100; i < value; i += 100) {
                _sb.append("......... ......... ......... ......... ......... "
                        + "......... ......... ......... .........           ");
                int k = i;
                for (int j = 1; (k != 0) && (j < 10); j++) {
                    _sb.setCharAt(i - j, (char) ('0' + (k % 10)));
                    k /= 10;
                }
            }
            for (i = i - 100; i < value; i++) {
                _sb.append(".");
            }
            if (_sb.length() != value) {
                throw new RuntimeException("oops");
            }
            ex.getValue().putString(_sb);
        } else {
            ex.getValue().put(value);
        }
    }

    public static void main(final String[] args) {
        new Stress8txn().runStandalone(args);
    }

    void inconsistent(TestResult result) {
        String fileName = "/tmp/out_" + Thread.currentThread().getName();
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(fileName));
            pw.println(result);
            pw.println();
            pw.println(mvvReport());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    String mvvReport() throws PersistitException {
        StringBuilder sb = new StringBuilder();
        Map<Long, List<String>> byTc = new TreeMap<Long, List<String>>();
        Map<Long, List<String>> byTs = new TreeMap<Long, List<String>>();
        TestShim.ignoreMVCC(true, _exs);
        Key key = _exs.getKey();
        _exs.clear().append("stress8txn");
        while (_exs.next(true) && key.getDepth() > 1 && key.reset().decodeString().equals("stress8xtn")) {
            List<Version> versions =  _exs.getValue().unpackMvvVersions();
            for (final Version v : versions) {
                List<String> list1 = byTc.get(v.getCommitTimestamp());
                if (list1 == null) {
                    list1 = new ArrayList<String>();
                    byTc.put(v.getCommitTimestamp(), list1);
                }
                list1.add(key.toString() + ": " + describe(v));
                
                List<String> list2 = byTs.get(v.getCommitTimestamp());
                if (list2 == null) {
                    list2 = new ArrayList<String>();
                    byTs.put(v.getCommitTimestamp(), list2);
                }
                list2.add(key.toString() + ": " + describe(v));
            }
        }
        mvvReportMap(byTc, "byTC", sb);
        mvvReportMap(byTs, "byTS", sb);
        TestShim.ignoreMVCC(false, _exs);
        return sb.toString();
    }
    
    private String describe(Version v) {
        if (v.getValue().isDefined() && v.getValue().isType(String.class)) {
            return v.toString().split("\\:")[0] + ":" + v.getValue().getString().length() + "$";
        } else {
            return v.toString();
        }
    }
    
    private void mvvReportMap(Map<Long, List<String>> map, String title, StringBuilder sb) {
        sb.append(String.format("%s\n\n", title));
        for (final Map.Entry<Long, List<String>> entry : map.entrySet()) {
            sb.append(String.format("%,15d ", entry.getKey()));
            boolean first = true;
            for (String s: entry.getValue()) {
                if (!first) {
                    sb.append(String.format("%16s", ""));
                } else {
                    first = false;
                }
                sb.append(String.format("%s\n", s));
            }
        }
        sb.append(String.format("\n\n"));
    }
}
