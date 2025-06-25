
/**
 *  Hubitat Flair Vents Driver
 *  Version 0.22
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
 *
 */

metadata {
    definition(name: 'Flair vents', namespace: 'bot.flair', author:  'Jaime Botero') {
        capability 'Refresh'
        capability 'SwitchLevel'
        capability 'VoltageMeasurement'

        attribute 'rssi', 'number'
        //attribute "percent-open-reason", "string"
        attribute 'connected-gateway-name', 'string'
        attribute 'has-buzzed', 'enum', ['true', 'false']
        attribute 'updated-at', 'string'
        attribute 'inactive', 'enum', ['true', 'false']
        attribute 'created-at', 'string'
        attribute 'percent-open', 'number'
        attribute 'setup-lightstrip', 'number'
        attribute 'motor-overdrive-ms', 'number'
        attribute 'duct-temperature-c', 'number'
        attribute 'duct-pressure', 'number'
        attribute 'firmware-version-s', 'number'
        attribute 'motor-run-time', 'number'
        attribute 'motor-current', 'number'

        attribute 'structure-id', 'string'
        attribute 'room-id', 'string'
        attribute 'room-name', 'string'
        attribute 'room-current-temperature-c', 'number'
        attribute 'room-starting-temperature-c', 'number'
        attribute 'room-conclusion-mode', 'string'
        attribute 'room-humidity-away-min', 'number'
        attribute 'room-type', 'string'
        attribute 'room-temp-away-min-c', 'number'
        attribute 'room-level', 'string'
        attribute 'room-hold-until', 'string'
        attribute 'room-away-mode', 'string'
        attribute 'room-heat-cool-mode', 'string'
        attribute 'room-updated-at', 'string'
        attribute 'room-state-updated-at', 'string'
        attribute 'room-set-point-c', 'number'
        attribute 'room-hold-until-schedule-event', 'string'
        attribute 'room-frozen-pipe-pet-protect', 'string'
        attribute 'room-created-at', 'string'
        attribute 'room-windows', 'string'
        attribute 'room-air-return', 'string'
        attribute 'room-current-humidity', 'number'
        attribute 'room-hold-reason', 'string'
        attribute 'room-occupancy-mode', 'string'
        attribute 'room-temp-away-max-c', 'number'
        attribute 'room-humidity-away-max', 'number'
        attribute 'room-preheat-precool', 'string'
        attribute 'room-active', 'string'
        attribute 'room-set-point-manual', 'string'
        attribute 'room-pucks-inactive', 'string'
        attribute 'room-occupied', 'number'
        attribute 'room-cooling-rate', 'number'
        attribute 'room-heating-rate', 'number'

        command 'setRoomActive', [[name: 'active*', type: 'ENUM', description: 'Set room active/away', constraints: ['true', 'false']]]
    }

    preferences {
        input 'devicePoll', 'number', title: 'Device Polling Interval',
            description: 'Change polling frequency of settings (minutes); 0 to disable polling',
            defaultValue:3, required: true, displayDuringSetup: true
        input name: 'debugOutput', type: 'bool', title: 'Enable Debug Logging?', defaultValue: false
    }
}

private logDebug(msg) {
  if (settings?.debugOutput) {
    log.debug "${device.label}: $msg"
  }
}

def setRefreshSchedule() {
  if (devicePoll == null) {
    device.updateSetting('devicePoll', 3)
  }
  unschedule(settingsRefresh)
  schedule("0 0/${devicePoll} * 1/1 * ? *", settingsRefresh)
}

def installed() {
  logDebug('installed')
  initialize()
}

def updated() {
  logDebug('updated')
  initialize()
}

def uninstalled() {
  logDebug('uninstalled')
}

def initialize() {
  logDebug('initialize')
  refresh()
}

def refresh() {
  logDebug('refresh')
  settingsRefresh()
  setRefreshSchedule()
}

def settingsRefresh() {
  parent.getDeviceData(device)
}

void setLevel(level, duration=null) {
  logDebug("setLevel to ${level}")
  parent.patchVent(device, level)
}

def getLastEventTime() {
  return state.lastEventTime
}

def setDeviceState(String attr, value) {
  logDebug("updating state -- ${attr}: ${value}")
  state[attr] = value
}

def getDeviceState(String attr) {
  if (state[attr]) {
    return state[attr]
  }
  refresh()
}

def setRoomActive(isActive) {
  logDebug("setRoomActive: ${isActive}")
  parent.patchRoom(device, isActive)
}

def updateParentPollingInterval(Integer intervalMinutes) {
  logDebug("Parent requesting polling interval change to ${intervalMinutes} minutes")
  
  // Update the internal setting without user intervention
  device.updateSetting('devicePoll', intervalMinutes)
  
  // Reschedule with new interval
  setRefreshSchedule()
}
