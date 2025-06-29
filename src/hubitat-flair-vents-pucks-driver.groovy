/**
 *  Hubitat Flair Pucks Driver
 *  Version 0.232
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
    definition(name: 'Flair pucks', namespace: 'bot.flair', author:  'Jaime Botero') {
        capability 'Refresh'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'MotionSensor'
        capability 'Battery'
        capability 'VoltageMeasurement'
        
        // Puck specific attributes
        attribute 'current-rssi', 'number'
        attribute 'rssi', 'number'
        attribute 'firmware-version-s', 'string'
        attribute 'inactive', 'enum', ['true', 'false']
        attribute 'created-at', 'string'
        attribute 'updated-at', 'string'
        attribute 'name', 'string'
        attribute 'gateway-connected', 'enum', ['true', 'false']
        attribute 'light-level', 'number'
        attribute 'air-pressure', 'number'
        
        // Room attributes
        attribute 'room-id', 'string'
        attribute 'room-name', 'string'
        attribute 'room-active', 'enum', ['true', 'false']
        attribute 'room-current-temperature-c', 'number'
        attribute 'room-current-humidity', 'number'
        attribute 'room-set-point-c', 'number'
        attribute 'room-set-point-manual', 'enum', ['true', 'false']
        attribute 'room-heat-cool-mode', 'string'
        attribute 'room-occupied', 'number'
        attribute 'room-occupancy-mode', 'string'
        attribute 'room-pucks-inactive', 'string'
        attribute 'room-frozen-pipe-pet-protect', 'enum', ['true', 'false']
        attribute 'room-preheat-precool', 'enum', ['true', 'false']
        attribute 'room-humidity-away-min', 'number'
        attribute 'room-humidity-away-max', 'number'
        attribute 'room-temp-away-min-c', 'number'
        attribute 'room-temp-away-max-c', 'number'
        attribute 'room-hold-reason', 'string'
        attribute 'room-hold-until-schedule-event', 'enum', ['true', 'false']
        attribute 'room-created-at', 'string'
        attribute 'room-updated-at', 'string'
        attribute 'room-state-updated-at', 'string'
        attribute 'structure-id', 'string'
        
        // Commands
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
