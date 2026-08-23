/**
 *  Hubitat Flair Vents Zone Summary Driver
 *  Version 0.240
 *
 *  Copyright 2024 Jaime Botero. All Rights Reserved
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import groovy.transform.Field

// Driver version string, surfaced on the device detail page so it can be
// reported in support requests (R7.1, R7.4, R7.5).
@Field static final String VERSION = '0.240'

// Opt-in DAB v2 observability surface (R14.1/R14.2/R13.1/R13.2): a SINGLE
// system-summary device carrying zone-wide rollups, created and updated by the
// parent Flair Vents app on every DAB v2 "balance" evaluation. Purely
// informational - nothing here is in the control path. Values are pushed by the
// parent via sendEvent; the driver itself has no behavior. Temperatures are in
// Celsius (internal control math).
metadata {
    definition(name: 'Flair Vents Zone Summary', namespace: 'bot.flair', author: 'Jaime Botero') {
        capability 'Sensor'

        attribute 'spreadC', 'number'         // predicted room-temperature spread (C)
        attribute 'maxErrorC', 'number'       // worst ACTIVE-room |signed error| (C)
        attribute 'status', 'string'          // evaluation status (idle | hold | recalculating), not an actuation receipt
        attribute 'recalc24h', 'number'       // recalculations in the last 24 h
        attribute 'hold24h', 'number'         // holds in the last 24 h
        attribute 'strategy', 'string'        // active control strategy (dab | balance)
        attribute 'avgSpreadC', 'number'      // strategy comparison metrics from the learning store, when available
        attribute 'maxSpreadC', 'number'
        attribute 'avgAdjustments', 'number'
        attribute 'avgMovement', 'number'
        attribute 'avgErrorC', 'number'
    }
}
