/**
 *  Hubitat Flair Vents Room Diagnostics Driver
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

// Opt-in DAB v2 observability surface (R13.3/R14.6): ONE device per managed
// room, created and updated by the parent Flair Vents app on every DAB v2
// "balance" evaluation. Purely informational - nothing here is in the control
// path. Values are pushed by the parent via sendEvent; the driver itself has no
// behavior. Internal control math is Celsius, so temperatures here are in C.
// proposedOpenPct is the EVALUATION'S plan for the room, not an actuation
// receipt - the dispatch layer may suppress the physical move (anti-chatter
// cooldown, batching, idempotency). Learned fields appear as the model learns
// and are absent until then.
metadata {
    definition(name: 'Flair Vents Room Diagnostics', namespace: 'bot.flair', author: 'Jaime Botero') {
        capability 'Sensor'

        attribute 'active', 'enum', ['true', 'false'] // room participates in balancing
        attribute 'temperature', 'number'        // room temperature (C)
        attribute 'signedErrorC', 'number'       // + still needs conditioning, - overcool/overheat (C)
        attribute 'proposedOpenPct', 'number'    // this evaluation's proposed vent opening (%)
        attribute 'airflowLimited', 'enum', ['true', 'false'] // vent near max but room still off target
        attribute 'coolingEfficiency', 'number'  // learned cooling baseline rate
        attribute 'heatingEfficiency', 'number'  // learned heating baseline rate
        attribute 'ventLeak', 'number'           // learned mean leak fraction of this room's vents
        attribute 'ventKnee', 'number'           // smallest learned knee aperture of this room's vents (%)
    }
}
