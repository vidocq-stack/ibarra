/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.ibarra.arquillian.fixtures;

public final class PrimitiveBean {
    private boolean booleanValue;
    public boolean getBooleanValue() { return booleanValue; }
    public void setBooleanValue(boolean value) { booleanValue=value; }
    private byte byteValue;
    public byte getByteValue() { return byteValue; }
    public void setByteValue(byte value) { byteValue=value; }
    private char charValue;
    public char getCharValue() { return charValue; }
    public void setCharValue(char value) { charValue=value; }
    private short shortValue;
    public short getShortValue() { return shortValue; }
    public void setShortValue(short value) { shortValue=value; }
    private int intValue;
    public int getIntValue() { return intValue; }
    public void setIntValue(int value) { intValue=value; }
    private long longValue;
    public long getLongValue() { return longValue; }
    public void setLongValue(long value) { longValue=value; }
    private float floatValue;
    public float getFloatValue() { return floatValue; }
    public void setFloatValue(float value) { floatValue=value; }
    private double doubleValue;
    public double getDoubleValue() { return doubleValue; }
    public void setDoubleValue(double value) { doubleValue=value; }
}
