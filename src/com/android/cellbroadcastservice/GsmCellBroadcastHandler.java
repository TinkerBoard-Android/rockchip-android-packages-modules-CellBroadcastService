/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastservice;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Telephony.CellBroadcasts;
import android.telephony.CbGeoUtils.Geometry;
import android.telephony.CellBroadcastIntents;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityGsm;
import android.telephony.CellInfo;
import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Pair;
import android.util.SparseArray;

import com.android.cellbroadcastservice.GsmSmsCbMessage.GeoFencingTriggerMessage;
import com.android.cellbroadcastservice.GsmSmsCbMessage.GeoFencingTriggerMessage.CellBroadcastIdentity;
import com.android.internal.annotations.VisibleForTesting;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Handler for 3GPP format Cell Broadcasts. Parent class can also handle CDMA Cell Broadcasts.
 */
public class GsmCellBroadcastHandler extends CellBroadcastHandler {
    private static final boolean VDBG = false;  // log CB PDU data

    /** Indicates that a message is not being broadcasted. */
    private static final String MESSAGE_NOT_BROADCASTED = "0";

    private SparseArray<String> mAreaInfos = new SparseArray<>();

    /** This map holds incomplete concatenated messages waiting for assembly. */
    private final HashMap<SmsCbConcatInfo, byte[][]> mSmsCbPageMap =
            new HashMap<>(4);

    @VisibleForTesting
    public GsmCellBroadcastHandler(Context context, Looper looper) {
        super("GsmCellBroadcastHandler", context, looper);
    }

    @Override
    protected void onQuitting() {
        super.onQuitting();     // release wakelock
    }

    /**
     * Handle a GSM cell broadcast message passed from the telephony framework.
     * @param message
     */
    public void onGsmCellBroadcastSms(int slotIndex, byte[] message) {
        sendMessage(EVENT_NEW_SMS_MESSAGE, slotIndex, -1, message);
    }

    /**
     * Get the area information
     *
     * @param slotIndex SIM slot index
     * @return The area information
     */
    @NonNull
    public String getCellBroadcastAreaInfo(int slotIndex) {
        String info = mAreaInfos.get(slotIndex);
        return info == null ? "" : info;
    }

    /**
     * Create a new CellBroadcastHandler.
     * @param context the context to use for dispatching Intents
     * @return the new handler
     */
    public static GsmCellBroadcastHandler makeGsmCellBroadcastHandler(Context context) {
        GsmCellBroadcastHandler handler = new GsmCellBroadcastHandler(context, Looper.myLooper());
        handler.start();
        return handler;
    }

    /**
     * Find the cell broadcast messages specify by the geo-fencing trigger message and perform a
     * geo-fencing check for these messages.
     * @param geoFencingTriggerMessage the trigger message
     *
     * @return {@code True} if geo-fencing is need for some cell broadcast message.
     */
    private boolean handleGeoFencingTriggerMessage(
            GeoFencingTriggerMessage geoFencingTriggerMessage, int slotIndex) {
        final List<SmsCbMessage> cbMessages = new ArrayList<>();
        final List<Uri> cbMessageUris = new ArrayList<>();

        SubscriptionManager subMgr = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int[] subIds = subMgr.getSubscriptionIds(slotIndex);
        Resources res;
        if (subIds != null) {
            res = getResources(subIds[0]);
        } else {
            res = getResources(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        }

        // Only consider the cell broadcast received within 24 hours.
        long lastReceivedTime = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS;

        // Some carriers require reset duplication detection after airplane mode or reboot.
        if (res.getBoolean(R.bool.reset_on_power_cycle_or_airplane_mode)) {
            lastReceivedTime = Long.max(lastReceivedTime, mLastAirplaneModeTime);
            lastReceivedTime = Long.max(lastReceivedTime,
                    System.currentTimeMillis() - SystemClock.elapsedRealtime());
        }

        // Find the cell broadcast message identify by the message identifier and serial number
        // and is not broadcasted.
        String where = CellBroadcasts.SERVICE_CATEGORY + "=? AND "
                + CellBroadcasts.SERIAL_NUMBER + "=? AND "
                + CellBroadcasts.MESSAGE_BROADCASTED + "=? AND "
                + CellBroadcasts.RECEIVED_TIME + ">?";

        ContentResolver resolver = mContext.getContentResolver();
        for (CellBroadcastIdentity identity : geoFencingTriggerMessage.cbIdentifiers) {
            try (Cursor cursor = resolver.query(CellBroadcasts.CONTENT_URI,
                    CellBroadcastProvider.QUERY_COLUMNS,
                    where,
                    new String[] { Integer.toString(identity.messageIdentifier),
                            Integer.toString(identity.serialNumber), MESSAGE_NOT_BROADCASTED,
                            Long.toString(lastReceivedTime) },
                    null /* sortOrder */)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        cbMessages.add(SmsCbMessage.createFromCursor(cursor));
                        cbMessageUris.add(ContentUris.withAppendedId(CellBroadcasts.CONTENT_URI,
                                cursor.getInt(cursor.getColumnIndex(CellBroadcasts._ID))));
                    }
                }
            }
        }

        log("Found " + cbMessages.size() + " not broadcasted messages since "
                + DateFormat.getDateTimeInstance().format(lastReceivedTime));

        List<Geometry> commonBroadcastArea = new ArrayList<>();
        if (geoFencingTriggerMessage.shouldShareBroadcastArea()) {
            for (SmsCbMessage msg : cbMessages) {
                if (msg.getGeometries() != null) {
                    commonBroadcastArea.addAll(msg.getGeometries());
                }
            }
        }

        // ATIS doesn't specify the geo fencing maximum wait time for the cell broadcasts specified
        // in geo fencing trigger message. We will pick the largest maximum wait time among these
        // cell broadcasts.
        int maximumWaitTimeSec = 0;
        for (SmsCbMessage msg : cbMessages) {
            maximumWaitTimeSec = Math.max(maximumWaitTimeSec, msg.getMaximumWaitingDuration());
        }

        if (DBG) {
            logd("Geo-fencing trigger message = " + geoFencingTriggerMessage);
            for (SmsCbMessage msg : cbMessages) {
                logd(msg.toString());
            }
        }

        if (cbMessages.isEmpty()) {
            if (DBG) logd("No CellBroadcast message need to be broadcasted");
            return false;
        }

        requestLocationUpdate(location -> {
            if (location == null) {
                // If the location is not available, broadcast the messages directly.
                for (int i = 0; i < cbMessages.size(); i++) {
                    broadcastMessage(cbMessages.get(i), cbMessageUris.get(i), slotIndex);
                }
            } else {
                for (int i = 0; i < cbMessages.size(); i++) {
                    List<Geometry> broadcastArea = !commonBroadcastArea.isEmpty()
                            ? commonBroadcastArea : cbMessages.get(i).getGeometries();
                    if (broadcastArea == null || broadcastArea.isEmpty()) {
                        broadcastMessage(cbMessages.get(i), cbMessageUris.get(i), slotIndex);
                    } else {
                        performGeoFencing(cbMessages.get(i), cbMessageUris.get(i), broadcastArea,
                                location, slotIndex);
                    }
                }
            }
        }, maximumWaitTimeSec);
        return true;
    }

    /**
     * Process area info message.
     *
     * @param slotIndex SIM slot index
     * @param message Cell broadcast message
     * @return {@code true} if the mssage is an area info message and got processed correctly,
     * otherwise {@code false}.
     */
    private boolean handleAreaInfoMessage(int slotIndex, SmsCbMessage message) {
        SubscriptionManager subMgr = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        // Check area info message
        int[] subIds = subMgr.getSubscriptionIds(slotIndex);
        Resources res;
        if (subIds != null) {
            res = getResources(subIds[0]);
        } else {
            res = getResources(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        }
        int[] areaInfoChannels = res.getIntArray(R.array.area_info_channels);

        if (IntStream.of(areaInfoChannels).anyMatch(
                x -> x == message.getServiceCategory())) {
            mAreaInfos.put(slotIndex, message.getMessageBody());

            String[] pkgs = mContext.getResources().getStringArray(
                    R.array.config_area_info_receiver_packages);
            for (String pkg : pkgs) {
                Intent intent = new Intent(CellBroadcastIntents.ACTION_AREA_INFO_UPDATED);
                intent.setPackage(pkg);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                        android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
            }
            return true;
        }

        // This is not an area info message.
        return false;
    }

    /**
     * Handle 3GPP-format Cell Broadcast messages sent from radio.
     *
     * @param message the message to process
     * @return true if need to wait for geo-fencing or an ordered broadcast was sent.
     */
    @Override
    protected boolean handleSmsMessage(Message message) {
        // For GSM, message.obj should be a byte[]
        int slotIndex = message.arg1;
        if (message.obj instanceof byte[]) {
            byte[] pdu = (byte[]) message.obj;
            SmsCbHeader header = createSmsCbHeader(pdu);
            if (header == null) return false;

            log("header=" + header);
            if (header.getServiceCategory() == SmsCbConstants.MESSAGE_ID_CMAS_GEO_FENCING_TRIGGER) {
                GeoFencingTriggerMessage triggerMessage =
                        GsmSmsCbMessage.createGeoFencingTriggerMessage(pdu);
                if (triggerMessage != null) {
                    return handleGeoFencingTriggerMessage(triggerMessage, slotIndex);
                }
            } else {
                SmsCbMessage cbMessage = handleGsmBroadcastSms(header, pdu, slotIndex);
                if (cbMessage != null) {
                    if (isDuplicate(cbMessage)) {
                        return false;
                    }

                    if (handleAreaInfoMessage(slotIndex, cbMessage)) {
                        log("Channel " + cbMessage.getServiceCategory() + " message processed");
                        return false;
                    }

                    handleBroadcastSms(cbMessage);
                    return true;
                }
                if (VDBG) log("Not handled GSM broadcasts.");
            }
        }
        return super.handleSmsMessage(message);
    }

    // return the GSM cell location from the first GSM cell info
    private Pair<Integer, Integer> getGsmLacAndCid() {
        TelephonyManager tm =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        List<CellInfo> infos = tm.getAllCellInfo();
        for (CellInfo info : infos) {
            CellIdentity ci = info.getCellIdentity();
            if (ci instanceof CellIdentityGsm) {
                CellIdentityGsm ciGsm = (CellIdentityGsm) ci;
                int lac = ciGsm.getLac() != CellInfo.UNAVAILABLE ? ciGsm.getLac() : -1;
                int cid = ciGsm.getCid() != CellInfo.UNAVAILABLE ? ciGsm.getCid() : -1;
                return Pair.create(lac, cid);
            }
        }
        return null;
    }


    /**
     * Handle 3GPP format SMS-CB message.
     * @param header the cellbroadcast header.
     * @param receivedPdu the received PDUs as a byte[]
     */
    private SmsCbMessage handleGsmBroadcastSms(SmsCbHeader header, byte[] receivedPdu,
            int slotIndex) {
        try {
            if (VDBG) {
                int pduLength = receivedPdu.length;
                for (int i = 0; i < pduLength; i += 8) {
                    StringBuilder sb = new StringBuilder("SMS CB pdu data: ");
                    for (int j = i; j < i + 8 && j < pduLength; j++) {
                        int b = receivedPdu[j] & 0xff;
                        if (b < 0x10) {
                            sb.append('0');
                        }
                        sb.append(Integer.toHexString(b)).append(' ');
                    }
                    log(sb.toString());
                }
            }

            if (VDBG) log("header=" + header);
            TelephonyManager tm =
                    (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            tm.createForSubscriptionId(getSubIdForPhone(mContext, slotIndex));
            // TODO make a systemAPI for getNetworkOperatorForSlotIndex
            String plmn = tm.getSimOperator();
            int lac = -1;
            int cid = -1;
            Pair<Integer, Integer> lacAndCid = getGsmLacAndCid();
            // Check if GSM lac and cid are available. This is required to support
            // dual-mode devices such as CDMA/LTE devices that require support for
            // both 3GPP and 3GPP2 format messages
            if (lacAndCid != null) {
                lac = lacAndCid.first;
                cid = lacAndCid.second;
            }

            SmsCbLocation location;
            switch (header.getGeographicalScope()) {
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_LOCATION_AREA_WIDE:
                    location = new SmsCbLocation(plmn, lac, -1);
                    break;

                case SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE:
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE:
                    location = new SmsCbLocation(plmn, lac, cid);
                    break;

                case SmsCbMessage.GEOGRAPHICAL_SCOPE_PLMN_WIDE:
                default:
                    location = new SmsCbLocation(plmn, -1, -1);
                    break;
            }

            byte[][] pdus;
            int pageCount = header.getNumberOfPages();
            if (pageCount > 1) {
                // Multi-page message
                SmsCbConcatInfo concatInfo = new SmsCbConcatInfo(header, location);

                // Try to find other pages of the same message
                pdus = mSmsCbPageMap.get(concatInfo);

                if (pdus == null) {
                    // This is the first page of this message, make room for all
                    // pages and keep until complete
                    pdus = new byte[pageCount][];

                    mSmsCbPageMap.put(concatInfo, pdus);
                }

                if (VDBG) log("pdus size=" + pdus.length);
                // Page parameter is one-based
                pdus[header.getPageIndex() - 1] = receivedPdu;

                for (byte[] pdu : pdus) {
                    if (pdu == null) {
                        // Still missing pages, exit
                        log("still missing pdu");
                        return null;
                    }
                }

                // Message complete, remove and dispatch
                mSmsCbPageMap.remove(concatInfo);
            } else {
                // Single page message
                pdus = new byte[1][];
                pdus[0] = receivedPdu;
            }

            // Remove messages that are out of scope to prevent the map from
            // growing indefinitely, containing incomplete messages that were
            // never assembled
            Iterator<SmsCbConcatInfo> iter = mSmsCbPageMap.keySet().iterator();

            while (iter.hasNext()) {
                SmsCbConcatInfo info = iter.next();

                if (!info.matchesLocation(plmn, lac, cid)) {
                    iter.remove();
                }
            }

            return GsmSmsCbMessage.createSmsCbMessage(mContext, header, location, pdus, slotIndex);

        } catch (RuntimeException e) {
            loge("Error in decoding SMS CB pdu", e);
            return null;
        }
    }

    private SmsCbHeader createSmsCbHeader(byte[] bytes) {
        try {
            return new SmsCbHeader(bytes);
        } catch (Exception ex) {
            loge("Can't create SmsCbHeader, ex = " + ex.toString());
            return null;
        }
    }

    /**
     * Holds all info about a message page needed to assemble a complete concatenated message.
     */
    private static final class SmsCbConcatInfo {

        private final SmsCbHeader mHeader;
        private final SmsCbLocation mLocation;

        SmsCbConcatInfo(SmsCbHeader header, SmsCbLocation location) {
            mHeader = header;
            mLocation = location;
        }

        @Override
        public int hashCode() {
            return (mHeader.getSerialNumber() * 31) + mLocation.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SmsCbConcatInfo) {
                SmsCbConcatInfo other = (SmsCbConcatInfo) obj;

                // Two pages match if they have the same serial number (which includes the
                // geographical scope and update number), and both pages belong to the same
                // location (PLMN, plus LAC and CID if these are part of the geographical scope).
                return mHeader.getSerialNumber() == other.mHeader.getSerialNumber()
                        && mLocation.equals(other.mLocation);
            }

            return false;
        }

        /**
         * Compare the location code for this message to the current location code. The match is
         * relative to the geographical scope of the message, which determines whether the LAC
         * and Cell ID are saved in mLocation or set to -1 to match all values.
         *
         * @param plmn the current PLMN
         * @param lac the current Location Area (GSM) or Service Area (UMTS)
         * @param cid the current Cell ID
         * @return true if this message is valid for the current location; false otherwise
         */
        public boolean matchesLocation(String plmn, int lac, int cid) {
            return mLocation.isInLocationArea(plmn, lac, cid);
        }
    }
}
