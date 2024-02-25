/**
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

import groovy.transform.Field

@Field static String BASE_URL = "https://api.flair.co"
@Field static String CONTENT_TYPE = "application/json"
@Field static String COOLING = "cooling"
@Field static String HEATING = "heating"
@Field static String PENDING_COOL = "pending cool"
@Field static String PENDING_HEAT = "pending heat"
@Field static String IDLE = "idle"
@Field static Double MINIMUM_PERCENTAGE_OPEN = 5.0
@Field static Double ROOM_RATE_CALC_MINIMUM_MINUTES = 2.5
@Field static Double MAXIMUM_PERCENTAGE_OPEN = 100.0
@Field static Double MAX_MINUTES_TO_SETPOINT = 60
@Field static Double ACCEPTABLE_SETPOINT_DEVIATION_C = 0.5
@Field static Double MAX_DIFFERENCE_IN_TEMPS_C = 15.0
@Field static Double MAX_TEMP_CHANGE_RATE_C = 2.0
@Field static Double MIN_TEMP_CHANGE_RATE_C = 0.0017
@Field static Double MINIMUM_COMBINED_VENT_FLOW_PERCENTAGE = 30.0
@Field static Double INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET = 2.5
@Field static Integer MAX_NUMBER_OF_STANDARD_VENTS = 15
@Field static Integer HTTP_TIMEOUT_SECS = 5

definition(
        name: 'Flair Vents',
        namespace: 'bot.flair',
        author: 'Jaime Botero',
        description: 'Provides discovery and control capabilities for Flair Vent devices',
        importUrl: 'https://raw.githubusercontent.com/ljbotero/hubitat-flair-vents/master/hubitat-flair-vents-app.groovy',
        category: 'Discovery',
        oauth: false,
        iconUrl: '',
        iconX2Url: '',
        iconX3Url: '',
        singleInstance: true
)

preferences {
    page(name: 'mainPage')
}

def mainPage() {
  dynamicPage(name: 'mainPage', title: 'Setup', install: true, uninstall: true) {
        section {
          input 'clientId', 'text', title: 'Client Id (OAuth 2.0)', required: true, submitOnChange: true
          input 'clientSecret', 'text', title: 'Client Secret OAuth 2.0', required: true, submitOnChange: true
          paragraph "<b><small>Obtain your client Id and secret from <a href='https://forms.gle/VohiQjWNv9CAP2ASA' target='_blank'>here<a/></b></small>"
        }

        if (settings?.clientId != null && settings?.clientSecret != null ) {
          login()
          unschedule(login)
          runEvery1Hour login
        }

        if (state.authError) {
          section {
            paragraph "<span style='color: red;'>${state.authError}</span>"
          }
        }

        if (state.flairAccessToken != null) {
          section {
              input 'discoverDevices', 'button', title: 'Discover', submitOnChange: true
          }
          listDiscoveredDevices()

          section("<h2>Dynamic Airflow Balancing</h2>") {
            input "dabEnabled", title: "Use Dynamic Airflow Balancing", submitOnChange: true, defaultValue: false, "bool"
            if (dabEnabled) {
              input "thermostat1", title: "Choose Thermostat for Vents",  multiple: false, required: true, "capability.thermostat"
              input name: "thermostat1TempUnit", type: "enum", title: "Units used by Thermostat", defaultValue: 2, options: [1:"Celsius (°C)",2:"Fahrenheit (°F)"]
              input "thermostat1AdditionalStandardVents", title: "Count of conventional Vents", submitOnChange: true, defaultValue: 0, "number"
              paragraph "<small>Enter the total number of standard (non-Flair) adjustable vents in the home associated with the chosen thermostat, excluding Flair vents. " + 
                "This value will ensure the combined airflow across all vents does not drop below a specified percent. It is used to maintain adequate airflow and prevent " +
                "potential frosting or other HVAC problems caused by lack of air movement.</small>"
              input "thermostat1CloseInactiveRooms", title: "Close vents on inactive rooms", submitOnChange: true, defaultValue: true, "bool"
              if (settings.thermostat1AdditionalStandardVents < 0) {
                app.updateSetting("thermostat1AdditionalStandardVents", 0)
              } else if (settings.thermostat1AdditionalStandardVents > MAX_NUMBER_OF_STANDARD_VENTS) {
                app.updateSetting("thermostat1AdditionalStandardVents", MAX_NUMBER_OF_STANDARD_VENTS)
              }
              if (!atomicState.thermostat1Mode || atomicState.thermostat1Mode == "auto") {
                patchStructureData(["mode": "manual"])
                atomicState.thermostat1Mode = "manual"
              }              
            } else if (!atomicState.thermostat1Mode || atomicState.thermostat1Mode == "manual") {
              patchStructureData(["mode": "auto"])
              atomicState.thermostat1Mode = "auto"
            }
            if (settings.thermostat1) {
              unsubscribe(settings.thermostat1,"thermostatOperatingState")
              subscribe(settings.thermostat1,"thermostatOperatingState", thermostat1ChangeStateHandler)
            }
          }          
        } else {
          section {
            paragraph 'Device discovery button is hidden until authorization is completed.'
          }
        }
        section{
          input name: "debugLevel", type: "enum", title: "Choose debug level", defaultValue: 0, options: [0:"None",1:"Level 1 (All)", 2:"Level 2", 3:"Level 3"], submitOnChange: true
        }
  }
}

def listDiscoveredDevices() {
    def children = getChildDevices()
    def builder = new StringBuilder()
    builder << "<ul>"
    children.each {
        if (it != null) {
            builder << "<li><a href='/device/edit/${it.getId()}'>${it.getLabel()}</a></li>"
        }
    }
    builder << "</ul>"
    def links = builder.toString()
    section {
        paragraph "Discovered devices are listed below:"
        paragraph links
    }
}

def updated() {
  log('Hubitat Flair App updating', 2)
}

def installed() {
  log('Hubitat Flair App installed', 2)
}

def uninstalled() {
  log('Hubitat Flair App uninstalling', 2)
  removeChildren()
  unschedule()
  unsubscribe()
}

def initialize(evt) {
  log(evt, 2)
}

// Helpers
private openAllVents(roomStates, percentOpen) {
  roomStates.each{ roomId, stateVal -> 
      stateVal.ventIds.each {
        def vent = getChildDevice(it)  
        if (!vent) return
        patchVent(vent, percentOpen)
      }
  }
}

private getPercentageOpen(ventIds) {
  def percentOpen = 0
  if (!ventIds || ventIds.size() == 0) return percentOpen
  ventIds.each {
    def vent = getChildDevice(it)
    if (vent) {
      percentOpen = percentOpen + (vent.currentValue("percent-open")).toInteger()      
    }
  }
  return percentOpen / ventIds.size()
}

private getRoomTemp(ventIds) {
  double currentTemp = 0d
  if (!ventIds || ventIds.size() == 0) return currentTemp
  ventIds.each {
    def vent = getChildDevice(it)
    if (vent) {
      currentTemp = currentTemp + vent.currentValue("room-current-temperature-c")
    }
  }
  return currentTemp / ventIds.size()
}

private atomicStateUpdate(stateKey, key, value) {
  atomicState.updateMapValue(stateKey, key, value)
  log("atomicStateUpdate(${stateKey}, ${key}, ${value}", 1)
}

private getTempOfColdestAndHottestRooms() {
  def tempColdestRoom = 0
  def tempHottestRoom = 0
  def roomStates = atomicState.roomState
  roomStates.each{ roomId, stateVal -> 
      stateVal.ventIds.each {
      def vent = getChildDevice(it)  
      if (!vent) return
      stateVal.lastStartTemp = vent.currentValue("room-current-temperature-c")
      if (tempHottestRoom < stateVal.lastStartTemp) {
        tempHottestRoom = stateVal.lastStartTemp
      }
      if (tempColdestRoom == 0 || tempColdestRoom > stateVal.lastStartTemp) {
        tempColdestRoom = stateVal.lastStartTemp
      }
    }
    atomicStateUpdate("roomState", roomId, stateVal)
  }
  return [tempColdestRoom: tempColdestRoom, tempHottestRoom: tempHottestRoom]
}

def getThermostatSetpoint(hvacMode) {
  double setpoint = hvacMode == COOLING ? 
    thermostat1.currentValue("coolingSetpoint") :
    thermostat1.currentValue("heatingSetpoint")  
  
  if (settings.thermostat1TempUnit == '2') {
    setpoint =  convertFahrenheitToCentigrades(setpoint)
  }
  setpoint = hvacMode == COOLING ? 
    setpoint - ACCEPTABLE_SETPOINT_DEVIATION_C : 
    setpoint + ACCEPTABLE_SETPOINT_DEVIATION_C
  return setpoint
}

def convertFahrenheitToCentigrades(tempValue) {
  return (tempValue - 32d) * (5d/9d)
}

def roundToNearestFifth(double num) {
  return Math.round(num / 5.0d) * 5.0d
}

def hasRoomReachedSetpoint(hvacMode, setpoint, currentVentTemp) {
  return (hvacMode == COOLING && setpoint >= currentVentTemp) || (hvacMode == HEATING && setpoint <= currentVentTemp)
}

void removeChildren() {
  def children = getChildDevices()
  log("Deleting all child devices: ${children}", 2)
  children.each {
        if (it != null) {
      deleteChildDevice it.getDeviceNetworkId()
        }
  }
}

// Level 1 is the most verbose
private log(msg, level = 3) {
  def settingsLevel = (settings?.debugLevel).toInteger()
  if (settingsLevel == 0) {
    return
  }
  if (settingsLevel <= level) {
    log.debug(msg)
  }
}

def isValidResponse(resp) {
  if (!resp) {
    log.error("HTTP Null response")
    return false
  } else if (resp.hasError()) {    
    def respCode = resp?.getStatus() ? resp.getStatus() : ""
    def respError = resp?.getErrorMessage() ? resp.getErrorMessage() : resp
    log.error("HTTP response code: ${respCode}, body: ${respError}")
    return false
  } 
  return true
}

def getDataAsync(uri, handler, data = null) {
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = CONTENT_TYPE
  def httpParams = [ uri: uri, headers: headers, contentType: contentType, timeout: HTTP_TIMEOUT_SECS ]
  asynchttpGet(handler, httpParams, data)
}

def patchDataAsync(uri, handler, body, data = null) {
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = CONTENT_TYPE
  def httpParams = [
    uri: uri, 
    headers: headers, 
    contentType: contentType, 
    requestContentType: contentType,
    timeout: HTTP_TIMEOUT_SECS,
    body: groovy.json.JsonOutput.toJson(body)
  ]
  asynchttpPatch(handler, httpParams, data)
  log("patchDataAsync:${uri}, body:${body}", 2)
}

def login() {
  autheticate()
  getStructureData()
}


// ### OAuth ###

def autheticate() {
  log('Getting access_token from Flair', 2)
  def uri = BASE_URL + '/oauth2/token'
  def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}&scope=vents.view+vents.edit+structures.view+structures.edit&grant_type=client_credentials"
  def params = [uri: uri, body: body, timeout: HTTP_TIMEOUT_SECS]
  try {
      httpPost(params) { response -> handleAuthResponse(response) }
      state.remove('authError')
    } catch (groovyx.net.http.HttpResponseException e) {
      String err = "Login failed - ${e.getLocalizedMessage()}: ${e.response.data}"
      log.error(err)
      state.authError = err
      return err
  }
  return ''
}

def handleAuthResponse(resp) {
  def respJson = resp.getData()
  //log("Authorized scopes: ${respJson.scope}", 1)
  state.flairAccessToken = respJson.access_token
}

// ### Get devices ###

def appButtonHandler(btn) {
  switch (btn) {
    case 'discoverDevices':
      discover()
      break
  }
}

private void discover() {
  log('Discovery started', 3)
  def uri = BASE_URL + '/api/vents'
  getDataAsync(uri, handleDeviceList)
}

def handleDeviceList(resp, data) {
  if (!isValidResponse(resp)) return
  def respJson = resp.getJson()
  respJson.data.each {
    def device = [:]
    device.id = it.id
    device.type = it.type
    device.label = it.attributes.name
    def dev = makeRealDevice(device)
    if (dev != null) {
      processVentTraits(dev, it)
    }
  }
}

def makeRealDevice(device) {
  def newDevice = getChildDevice(device.id)
  if (!newDevice) {
    def deviceType = "Flair ${device.type}"
    newDevice = addChildDevice('bot.flair', deviceType.toString(), device.id, [name: device.label, label: device.label])
  }
  return newDevice
}

def getDeviceData(device) {
  log("Refresh device details for ${device}", 2)
  def deviceId = device.getDeviceNetworkId()

  def uri = BASE_URL + '/api/vents/' + deviceId + '/current-reading'
  getDataAsync(uri, handleDeviceGet, [device: device])

  uri = BASE_URL + '/api/vents/' + deviceId + '/room'
  getDataAsync(uri, handleRoomGet, [device: device])
}

// ### Get device data ###

def handleRoomGet(resp, data) {
  if (!isValidResponse(resp)) return
  processRoomTraits(data.device, resp.getJson())
}

def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp)) return
  processVentTraits(data.device, resp.getJson())
}

def traitExtract(device, details, propNameData, propNameDriver = propNameData, unit = null) {
  def propValue = details.data.attributes[propNameData]
  if (propValue != null) {
    if (unit) {
      sendEvent(device, [name: propNameDriver, value: propValue, unit: unit])
    } else {
      sendEvent(device, [name: propNameDriver, value: propValue])
    }
  }
}

def processVentTraits(device, details) {
 log("Processing Vent data for ${device}: ${details}", 1)

  if (!details.data) return
  traitExtract(device, details, 'firmware-version-s')
  traitExtract(device, details, 'rssi')
  traitExtract(device, details, 'connected-gateway-puck-id')
  traitExtract(device, details, 'created-at')
  traitExtract(device, details, 'duct-pressure')
  traitExtract(device, details, 'percent-open', 'percent-open', '%')
  traitExtract(device, details, 'duct-temperature-c')
  traitExtract(device, details, 'motor-run-time')
  traitExtract(device, details, 'system-voltage')
  traitExtract(device, details, 'motor-current')
  traitExtract(device, details, 'has-buzzed')
  traitExtract(device, details, 'updated-at')
  traitExtract(device, details, 'inactive')
}

def processRoomTraits(device, details) {
  log("Processing Room data for ${device}: ${details}", 1)

  if (!details?.data) return
  def roomId = details.data.id
  sendEvent(device, [name: 'room-id', value: roomId])  
  traitExtract(device, details, 'name', 'room-name')
  traitExtract(device, details, 'current-temperature-c', 'room-current-temperature-c')
  traitExtract(device, details, 'room-conclusion-mode')
  traitExtract(device, details, 'humidity-away-min', 'room-humidity-away-min')
  traitExtract(device, details, 'room-type')
  traitExtract(device, details, 'temp-away-min-c', 'room-temp-away-min-c')
  traitExtract(device, details, 'level', 'room-level')
  traitExtract(device, details, 'hold-until', 'room-hold-until')
  traitExtract(device, details, 'room-away-mode')
  traitExtract(device, details, 'heat-cool-mode', 'room-heat-cool-mode')
  traitExtract(device, details, 'updated-at', 'room-updated-at')
  traitExtract(device, details, 'state-updated-at', 'room-state-updated-at')
  traitExtract(device, details, 'set-point-c', 'room-set-point-c')
  traitExtract(device, details, 'hold-until-schedule-event', 'room-hold-until-schedule-event')
  traitExtract(device, details, 'frozen-pipe-pet-protect', 'room-frozen-pipe-pet-protect')
  traitExtract(device, details, 'created-at', 'room-created-at')
  traitExtract(device, details, 'windows', 'room-windows')
  traitExtract(device, details, 'air-return', 'room-air-return')
  traitExtract(device, details, 'current-humidity', 'room-current-humidity')
  traitExtract(device, details, 'hold-reason', 'room-hold-reason')
  traitExtract(device, details, 'occupancy-mode', 'room-occupancy-mode')
  traitExtract(device, details, 'temp-away-max-c', 'room-temp-away-max-c')
  traitExtract(device, details, 'humidity-away-max', 'room-humidity-away-max')
  traitExtract(device, details, 'preheat-precool', 'room-preheat-precool')
  traitExtract(device, details, 'active', 'room-active')
  traitExtract(device, details, 'set-point-manual', 'room-set-point-manual')
  traitExtract(device, details, 'pucks-inactive', 'room-pucks-inactive')

  if (details?.data?.relationships?.structure?.data) {
    def structureId = details.data.relationships.structure.data.id
    sendEvent(device, [name: 'structure-id', value: structureId])
  }
  def remoteSensor = details?.data?.relationships['remote-sensors']?.data?.first()
  if (remoteSensor) {
    uri = BASE_URL + '/api/remote-sensors/'+ remoteSensor.id +'/sensor-readings'
    getDataAsync(uri, handleRemoteSensorGet, [device: device])
  }

  updateByRoomIdState(details)
}

def handleRemoteSensorGet(resp, data) {
  if (!isValidResponse(resp)) return
  def details = resp?.getJson()
  def propValue = details?.data?.first()?.attributes['occupied']
  //log("handleRemoteSensorGet: ${details}", 1)
  sendEvent(data.device, [name: 'room-occupied', value: propValue])
}

def updateByRoomIdState(details) {  
  if (!details?.data?.relationships?.vents?.data) return
  def roomId = details.data.id
  def ventIds = []
  details.data.relationships.vents.data.each {
    ventIds.add(it.id)
  }
  if (!atomicState.roomState?."${roomId}") {
    def roomVents = [
      roomName: details.data.attributes.name, 
      ventIds: ventIds, 
      heatingRate: 0, 
      coolingRate: 0, 
      lastStartTemp: 0,
      percentOpen: 0
    ]
    atomicStateUpdate("roomState", roomId, roomVents)  
  }
  //log(atomicState.roomState, 1)
}

// ### Operations ###

def patchStructureData(attributes) {
  if (!state.structureId) return
  def body = [data: [type: "structures", attributes: attributes]]
  def uri = BASE_URL + "/api/structures/${state.structureId}"
  patchDataAsync(uri, null, body)
}

def getStructureData() {
  def uri = BASE_URL + '/api/structures'
  getDataAsync(uri, handleStructureGet)
}

def handleStructureGet(resp, data) {
  if (!isValidResponse(resp)) return
  def response = resp.getJson()
  //log("handleStructureGet: ${response}", 1)
  if (!response?.data) {
    return
  }
  def myStruct = resp.getJson().data.first()
  if (!myStruct?.attributes) {
    return
  }
  state.structureId = myStruct.id
}

def patchVent(device, percentOpen) {  
  log("Setting percent open for ${device} to ${percentOpen}%", 3)
  if (percentOpen > 100) {
    percentOpen = 100
  } else if (percentOpen  < 0) {
    percentOpen = 0
  }
  def deviceId = device.getDeviceNetworkId()
  def uri = BASE_URL + '/api/vents/' + deviceId
  def body = [
    data: [
      type: "vents", 
      attributes: [
        "percent-open": (percentOpen).toInteger()
      ]
    ]
  ]
  patchDataAsync(uri, handleVentPatch, body, [device: device])
}

def handleVentPatch(resp, data) {
  if (!isValidResponse(resp)) return  
  traitExtract(data.device, resp.getJson(), 'percent-open', '%')
}

def patchRoom(device, active) {
  def roomId = device.currentValue("room-id")
  if (!roomId || active == null) return
  def roomName = device.currentValue("room-name")
  log("Setting active state to ${active} for '${roomName}'", 3)
  
  def uri = BASE_URL+ '/api/rooms/' + roomId
  def body = [
    data: [
      type: "rooms", 
      attributes: [
        "active": active == 'true' ? true: false
      ]
    ]
  ] 
  patchDataAsync(uri, handleRoomPatch, body, [device: device])
}

def handleRoomPatch(resp, data) {
  if (!isValidResponse(resp)) return
  traitExtract(data.device, resp.getJson(), 'active', 'room-active')
}

def thermostat1ChangeStateHandler(evt) {
  log("thermostat changed state to:${evt.value}", 3)
  def hvacMode = evt.value
  if (hvacMode == PENDING_COOL) {
    hvacMode = COOLING
  } else if (hvacMode == PENDING_HEAT) {
    hvacMode = HEATING
  }
  switch(hvacMode) {
    case COOLING:
    case HEATING:
      if (atomicState.thermostat1State) {
        log("initializeRoomStates has already been executed (${evt.value})",3)
        return
      }
      atomicStateUpdate("thermostat1State", "mode", hvacMode)
      atomicStateUpdate("thermostat1State", "startTime", now())
      runInMillis(1000, 'initializeRoomStates', [data: hvacMode]) // wait a bit since setpoint is set a few ms later
      unschedule(checkActiveRooms)
      if (settings.thermostat1CloseInactiveRooms == true) {
        runEvery5Minutes("checkActiveRooms")
      }
      break
    default:
      unschedule(checkActiveRooms)
      if (atomicState.thermostat1State)  {        
        if (atomicState.thermostat1State?.startTime) { 
          atomicStateUpdate("thermostat1State", "endTime", now())
          finalizeRoomStates(
            atomicState.roomState,
            atomicState.thermostat1State.startTime,
            atomicState.thermostat1State.endTime, 
            atomicState.thermostat1State.mode)
        }
        atomicState.remove('thermostat1State')
      }
      break
  }
}

// ### Dynamic Airflow Balancing ###

def finalizeRoomStates(roomStates, startTime, endTime, hvacMode) {
  log("Finalizing room states", 3)
  if (!atomicState.roomState) {
    return
  }
  def totalMinutes = (endTime - startTime) / (1000 * 60)
  log("HVAC ran for ${totalMinutes} minutes", 3)
  if (totalMinutes >= ROOM_RATE_CALC_MINIMUM_MINUTES) {
    roomStates.each{roomId, stateVal -> 
      def percentOpen = getPercentageOpen(stateVal.ventIds)
      def currentTemp = getRoomTemp(stateVal.ventIds)
      def rate = calculateRoomChangeRate(currentTemp, 
          stateVal.lastStartTemp, totalMinutes, percentOpen)
      if (rate < 0) {
        return
      } else if (hvacMode == COOLING) {
        stateVal.coolingRate = rate
      } else if (hvacMode == HEATING) {
        stateVal.heatingRate = rate
      } else {
        return
      }
      // Collect metric to determine steep function
      atomicStateUpdate("roomState", roomId, stateVal)
    }
  }  
}

def initializeRoomStates(hvacMode) {
  log("Initializing room states - hvac mode: ${hvacMode})", 3)
  if (!atomicState.roomState) return
  // Get the target temperature from the thermostat
  double setpoint = getThermostatSetpoint(hvacMode)
  def tempData = getTempOfColdestAndHottestRooms()
  if (hvacMode == COOLING && tempData.tempColdestRoom < setpoint) {
    setpoint = tempData.tempColdestRoom
    log("Setting setpoint to coldest room at ${setpoint}C", 3)
  } else if (hvacMode == HEATING && tempData.tempHottestRoom > setpoint) {
    setpoint = tempData.tempHottestRoom
    log("Setting setpoint to hottest room at ${setpoint}C", 3)
  }

  // Get longest time to reach to target temp
  def longestTimeToGetToTarget = calculateLongestMinutesToTarget(hvacMode, setpoint)
  if (longestTimeToGetToTarget <= 0) {
    log("Openning all vents (setpoint: ${setpoint})", 3)
    openAllVents(atomicState.roomState, MAXIMUM_PERCENTAGE_OPEN)
    return
  }
  log("Initializing room states - setpoint: ${setpoint}, longestTimeToGetToTarget: ${longestTimeToGetToTarget}", 3)

  // Calculate percent open for each vent vents proportionally
  calculateOpenPercentageForAllVents(hvacMode, setpoint, longestTimeToGetToTarget)

  // Ensure mimimum combined vent flow across vents
  adjustVentOpeningsToEnsureMinimumAirflowTarget(settings.thermostat1AdditionalStandardVents)

  // Apply open percentage across all vents
  if (dabEnabled) {
    atomicState.roomState.each{roomId, stateVal -> 
      stateVal.ventIds.each {
        def vent = getChildDevice(it)
        if (vent) {
          patchVent(vent, stateVal.percentOpen)
        }
      }
    }  
  }
}

def adjustVentOpeningsToEnsureMinimumAirflowTarget(additionalStandardVents) {
  def totalDeviceCount = additionalStandardVents > 0 ? additionalStandardVents: 0
  def sumPercentages = totalDeviceCount * 100 // Assuming all standard vents are at 100%
  atomicState.roomState.each{roomId, stateVal -> 
    stateVal.ventIds.each {
      totalDeviceCount++
      if (stateVal.percentOpen) {
        sumPercentages += stateVal.percentOpen
      }
    }
  }
  if (totalDeviceCount <= 0) {
    log("totalDeviceCount is zero", 3)
    return
  }
  def combinedVentFlowPercentage = (100 * sumPercentages) / (totalDeviceCount * 100) 
  if (combinedVentFlowPercentage >= MINIMUM_COMBINED_VENT_FLOW_PERCENTAGE) {
    log("Combined vent flow percentage (${combinedVentFlowPercentage}) is greather than ${MINIMUM_COMBINED_VENT_FLOW_PERCENTAGE}", 3)
    return
  }
  log("Combined Vent Flow Percentage (${combinedVentFlowPercentage}) is lower than ${MINIMUM_COMBINED_VENT_FLOW_PERCENTAGE}%", 3)
  def targetPercentSum = MINIMUM_COMBINED_VENT_FLOW_PERCENTAGE * totalDeviceCount
  def diffPercentageSum = targetPercentSum - sumPercentages
  log("sumPercentages=${sumPercentages}, targetPercentSum=${targetPercentSum}, diffPercentageSum=${diffPercentageSum}", 2)
  def continueAdjustments = true
  while (diffPercentageSum > 0 && continueAdjustments) { 
    continueAdjustments = false     
    atomicState.roomState.each{roomId, stateVal -> 
      stateVal.ventIds.each {
        def percentOpenVal = stateVal.percentOpen ? stateVal.percentOpen: 0
        if (percentOpenVal < MAXIMUM_PERCENTAGE_OPEN) {
          stateVal.percentOpen = percentOpenVal + INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET
          diffPercentageSum = diffPercentageSum - INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET
          continueAdjustments = true
          log("Adjusting % open for `${stateVal.roomName}` from " + 
            "${stateVal.percentOpen - INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET}% " +
            "to ${stateVal.percentOpen}%", 2)
        }
      }
      atomicStateUpdate("roomState", roomId, stateVal)
    }
  }
}

def calculateOpenPercentageForAllVents(hvacMode, setpoint, longestTimeToGetToTarget) {
  def roomStates = atomicState.roomState
  roomStates.each{roomId, stateVal -> 
    stateVal.ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) return
      def rate = hvacMode == COOLING ? stateVal.coolingRate : stateVal.heatingRate
      def percentageOpen = calculateVentOpenPercentange(setpoint, hvacMode, 
          rate, stateVal.lastStartTemp, longestTimeToGetToTarget)
      def isRoomActive = vent.currentValue("room-active") == "true"
      if (settings.thermostat1CloseInactiveRooms == true && !isRoomActive) {
        def roomName = stateVal.roomName
        log("Closing vent on inactive room (${roomName})", 3)
        percentageOpen = MINIMUM_PERCENTAGE_OPEN
      }
      stateVal.percentOpen = percentageOpen
    }
    atomicStateUpdate("roomState", roomId, stateVal)
  }  
}

def calculateVentOpenPercentange(setpoint, hvacMode, rate, startTemp, longestTimeToGetToTarget) {  
  def percentageOpen = MAXIMUM_PERCENTAGE_OPEN
  if (Math.abs(setpoint - startTemp) > MAX_DIFFERENCE_IN_TEMPS_C) {
    log("Difference between start room temp (${startTemp}) and setpoint (${setpoint}) is too high", 3)
    return percentageOpen
  }
  if (setpoint > 0 && rate > 0 && longestTimeToGetToTarget > 0) {
    if (hasRoomReachedSetpoint(hvacMode, setpoint, startTemp)) {
      log("Room is already warmer/cooler (${startTemp}) than setpoint (${setpoint})", 3)
      percentageOpen = MINIMUM_PERCENTAGE_OPEN
    } else {
      percentageOpen =  Math.abs(setpoint - startTemp) / (rate * longestTimeToGetToTarget)            
      percentageOpen = roundToNearestFifth(percentageOpen * 100)
      if (percentageOpen < MINIMUM_PERCENTAGE_OPEN) {
        percentageOpen = MINIMUM_PERCENTAGE_OPEN
      } else if (percentageOpen > MAXIMUM_PERCENTAGE_OPEN) {
        percentageOpen = MAXIMUM_PERCENTAGE_OPEN
      }
    }    
  }
  log("Open Percentange: ${Math.abs(setpoint - startTemp) / (rate * longestTimeToGetToTarget)}" + 
      " = (${setpoint} - ${startTemp}) / ( ${rate} * ${longestTimeToGetToTarget} )", 2)
  return percentageOpen
}

def checkActiveRooms() {
  if (!atomicState.roomState) return
  atomicState.roomState.each{roomId, stateVal -> 
    stateVal.ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) return
      boolean isRoomActive = vent.currentValue("room-active") == "true"
      def currPercentOpen = (vent.currentValue("percent-open")).toInteger()
      def calculatedPercentOpen = stateVal.percentOpen
      String roomName = stateVal.roomName
      if (settings.thermostat1CloseInactiveRooms == true && 
        !isRoomActive && currPercentOpen > MINIMUM_PERCENTAGE_OPEN) {
        log("Closing vent on inactive room (${roomName})", 3)
        patchVent(vent, MINIMUM_PERCENTAGE_OPEN)
      }
    }    
  }
}

def calculateLongestMinutesToTarget(hvacMode, setpoint) {
  def longestTimeToGetToTarget = 0
  def roomStates = atomicState.roomState
  roomStates.each{ roomId, stateVal -> 
      log("atomicState.roomState: roomId=${roomId}, roomState=${stateVal}", 2)
      stateVal.ventIds.each {
        def vent = getChildDevice(it)  
        if (!vent) return
        def minutesToTarget = 0
        def rate = hvacMode == COOLING ? stateVal.coolingRate : stateVal.heatingRate
        if (!hasRoomReachedSetpoint(hvacMode, setpoint, stateVal.lastStartTemp) && rate > 0) {
          minutesToTarget = Math.abs(setpoint - stateVal.lastStartTemp) / rate
        }
        if (minutesToTarget > MAX_MINUTES_TO_SETPOINT) {
            minutesToTarget = MAX_MINUTES_TO_SETPOINT
            log("A very long time to target reached limit of ${MAX_MINUTES_TO_SETPOINT} minutes", 3)
        } else if (minutesToTarget < 0) {
          minutesToTarget = 0
        }
        if (longestTimeToGetToTarget < minutesToTarget){
          longestTimeToGetToTarget = minutesToTarget
        }
        log("atomicState.roomState: vent=${vent}, roomTemp=${stateVal.lastStartTemp}", 3)
      }    
  }
  return longestTimeToGetToTarget
}

def calculateRoomChangeRate(currentTemp, lastStartTemp, totalMinutes, percentOpen) {
  double diffTemps = Math.abs(lastStartTemp - currentTemp)
  double rate = diffTemps / totalMinutes
  if (percentOpen <= MINIMUM_PERCENTAGE_OPEN) {
    log("Vent was opened less than ${MINIMUM_PERCENTAGE_OPEN}% (${percentOpen}), therefore it's being excluded", 3)
    return -1
  }

  double A = 0.0524
  double B = 2.75
  double P = percentOpen / 100

  double equivalentExpRate = (Math.log(P/A))/B  
  double approxMaxRate = (Math.log(1/A))/B
  double approxEquivMaxRate = (approxMaxRate * rate) / equivalentExpRate

  if (approxEquivMaxRate > MAX_TEMP_CHANGE_RATE_C) {
    log("Change rate (${approxEquivMaxRate}) is greater than ${MAX_TEMP_CHANGE_RATE_C}, therefore it's being excluded", 3)
    return -1
  } else if (approxEquivMaxRate < MIN_TEMP_CHANGE_RATE_C) {
    log("Change rate (${approxEquivMaxRate}) is lower than ${MIN_TEMP_CHANGE_RATE_C}, therefore it's being excluded", 3)
    return -1
  }   
  return approxEquivMaxRate
}