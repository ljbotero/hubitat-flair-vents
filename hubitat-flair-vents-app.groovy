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
@Field static String IDLE = "idle"
@Field static Double MINIMUM_PERCENTAGE_OPEN = 5
@Field static Double MAXIMUM_PERCENTAGE_OPEN = 100
@Field static Double MAX_MINUTES_TO_SETPOINT = 60 * 6
@Field static Double ACCEPTABLE_SETPOINT_DEVIATION_C = 0.5

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

        if (state?.authError) {
          section {
            paragraph "<span style='color: red;'>${state?.authError}</span>"
          }
        }

        if (state?.flairAccessToken != null) {
          section {
              input 'discoverDevices', 'button', title: 'Discover', submitOnChange: true
          }
          listDiscoveredDevices()

          section("<h2>Dynamic Airflow Balancing (Beta)</h2>") {
            input "dabEnabled", title: "Use Dynamic Airflow Balancing", submitOnChange: true, defaultValue: false, "bool"
            if (dabEnabled) {
              input "thermostat1", title: "Choose Thermostat for Vents",  multiple: false, required: false, "capability.thermostat"
              input name: "thermostat1TempUnit", type: "enum", title: "Units used by Thermostat", defaultValue: 2, options: [1:"Celsius (°C)",2:"Fahrenheit (°F)"]
              input "thermostat1CloseInactiveRooms", title: "Close vents on inactive rooms", submitOnChange: true, defaultValue: true, "bool"
              if (!state.thermostat1Mode || state.thermostat1Mode == "auto") {
                patchStructureData(["mode": "manual"])
                state.thermostat1Mode = "manual"
              }              
            } else if (!state.thermostat1Mode || state.thermostat1Mode == "manual") {
              patchStructureData(["mode": "auto"])
              state.thermostat1Mode = "auto"
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

private getTempOfColdestAndHottestRooms(roomStates) {
  def tempColdestRoom = 0
  def tempHottestRoom = 0
  roomStates.each{ roomId, stateVal -> 
      stateVal.ventIds.each {
      def vent = getChildDevice(it)  
      if (vent) {
        stateVal.lastStartTemp = vent.currentValue("room-current-temperature-c")
        if (tempHottestRoom < stateVal.lastStartTemp) {
          tempHottestRoom = stateVal.lastStartTemp
        }
        if (tempColdestRoom == 0 || tempColdestRoom > stateVal.lastStartTemp) {
          tempColdestRoom = stateVal.lastStartTemp
        }
      }
    }
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
  // With a 0.5 degrees of wiggle room
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
  if (settings?.debugLevel == 0) return
  if (settings?.debugLevel <= level) {
    log.debug(msg)
  }
}

def isValidResponse(resp) {
  if (!resp) {
    log.error("HTTP Null response")
    return false
  } else if (resp.hasError()) {    
    def respCode = resp.getStatus()
    def respError = resp.getErrorData()
    log.error("HTTP response code: ${respCode}, body: ${respError}")
    return false
  } 
  return true
}

def getDataAsync(uri, handler, data = null) {
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = CONTENT_TYPE
  def httpParams = [ uri: uri, headers: headers, contentType: contentType ]
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
    body: groovy.json.JsonOutput.toJson(body)
  ]
  asynchttpPatch(handler, httpParams, data)
  log("patchDataAsync:${uri}, body:${body}", 1)
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
  def params = [uri: uri, body: body]
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
  log("Authorized scopes: ${respJson.scope}", 1)
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
  log("Refresh device details for ${device}", 1)
  def deviceId = device.getDeviceNetworkId()

  def uri = BASE_URL + '/api/vents/' + deviceId + '/current-reading'
  getDataAsync(uri, handleDeviceGet, [device: device])

  uri = BASE_URL + '/api/vents/' + deviceId + '/room'
  getDataAsync(uri, handleRoomGet, [device: device])
}

// ### Get device data ###

def handleRoomGet(resp, data) {
  if (!isValidResponse(resp)) return
  fullDevice = getChildDevice(data.device.getDeviceNetworkId())
  processRoomTraits(fullDevice, resp.getJson())
}

def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp)) return
  fullDevice = getChildDevice(data.device.getDeviceNetworkId())
  processVentTraits(fullDevice, resp.getJson())
}

def traitExtract(device, details, propNameData, propNameDriver = propNameData, unit = null) {
  def propValue = details.data.attributes[propNameData]
  if (propValue != null) {
    if (unit) {
      sendEvent(device, [name: propNameDriver, value: propValue, unit: unit])
    } else {
      sendEvent(device, [name: propNameDriver, value: propValue])
    }
    log("${propName} = ${propValue}", 1)
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

  updateByRoomIdState(details)
}

def updateByRoomIdState(details) {  
  if (!details?.data?.relationships?.vents?.data) return
  def roomId = details.data.id
  def ventIds = []
  details.data.relationships.vents.data.each {
    ventIds.add(it.id)
  }
  def roomVents = [roomName: details.data.attributes.name, ventIds: ventIds, heatingRate: 0, coolingRate: 0, lastStartTemp: 0]
  if (!state.roomState) {
     state.roomState = ["${roomId}": roomVents]
  } else if (!state.roomState[roomId]) {
    state.roomState["${roomId}"] = roomVents
  } else {
    return
  }
  def roomState = state.roomState // workaround to force the state to update
  state.roomState = roomState
  log(state.roomState, 1)
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
  log("handleStructureGet: ${response}", 1)
  if (!response?.data) {
    return
  }
  def myStruct = resp.getJson().data.first()
  if (!myStruct?.attributes) {
    return
  }
  state.structureId = myStruct.id
}

def patchVent(com.hubitat.app.DeviceWrapper device, percentOpen) {  
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
        "percent-open": percentOpen
      ]
    ]
  ]
  patchDataAsync(uri, handleVentPatch, body, [device: device])
}

def handleVentPatch(resp, data) {
  if (!isValidResponse(resp)) return
  fullDevice = getChildDevice(data.device.getDeviceNetworkId())
  traitExtract(fullDevice, resp.getJson(), 'percent-open', '%')
}

def patchRoom(com.hubitat.app.DeviceWrapper device, active) {
  def roomId = device.currentValue("room-id")
  log("Setting room attributes for ${roomId} to active:${active}%", 3)
  if (!roomId || active == null) {
    return
  }
  
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
  fullDevice = getChildDevice(data.device.getDeviceNetworkId())
  traitExtract(fullDevice, resp.getJson(), 'active', 'room-active')
}

def thermostat1ChangeStateHandler(evt) {
  log("thermostat changed state to:${evt.value}", 2)
  switch(evt.value) {
    case COOLING:
    case HEATING:
      state.thermostat1State = [mode: evt.value, startTime: now()]
      runInMillis(1000, 'initializeRoomStates', [data: evt.value]) // wait a bit since setpoint is set a few ms later
      unschedule(checkActiveRooms)
      if (settings.thermostat1CloseInactiveRooms == true) {
        runEvery5Minutes("checkActiveRooms")
      }
      break
    case IDLE:
      unschedule(checkActiveRooms)
      if (state.thermostat1State)  {        
        def lastMode = state.thermostat1State.mode
        state.thermostat1State.mode = evt.value
        state.thermostat1State.endTime = now()
        if (lastMode == COOLING || lastMode == HEATING) {
          finalizeRoomStates()
        }
      }
      break    
  }
}

// ### Dynamic Airflow Balancing ###

def finalizeRoomStates() {
  log("Finalizing room states", 3)
  if (!state.roomState || !state.thermostat1State) {
    return
  }
  def totalMinutes = (now() - state.thermostat1State.startTime) / (1000 * 60)
  log("HVAC ran for ${totalMinutes} minutes", 3)
  if (totalMinutes < 5) {
    // If it only ran for 5 minutes discard it
    return
  }
  def roomState = state.roomState // workaround to force the state to update
  roomState.each{roomId, stateVal -> 
      roomState["${roomId}"] = calculateRoomChangeRate(roomId, stateVal, totalMinutes)
  }
  state.roomState = roomState
}

def initializeRoomStates(hvacMode) {
  log("Initializing room states - hvac mode: ${hvacMode})", 3)
  if (!state.roomState) return
  // Get the target temperature from the thermostat
  double setpoint = getThermostatSetpoint(hvacMode)
  def tempData = getTempOfColdestAndHottestRooms(state.roomState)
  if (hvacMode == COOLING && tempData.tempColdestRoom < setpoint) {
    setpoint = tempData.tempColdestRoom
    log("Setting setpoint to coldest room at ${setpoint}C", 3)
  } else if (hvacMode == HEATING && tempData.tempHottestRoom > setpoint) {
    setpoint = tempData.tempHottestRoom
    log("Setting setpoint to hottest room at ${setpoint}C", 3)
  }

  // Get the current temperature from each room
  def longestTimeToGetToTarget = captureRoomTempsAndCalculateLongestMinutesToTarget(hvacMode, setpoint)
  if (longestTimeToGetToTarget <= 0) {
    log("Keeping vents unchanged (setpoint: ${setpoint}, longestTimeToGetToTarget: ${longestTimeToGetToTarget})", 3)
    return
  }
  log("Initializing room states - setpoint: ${setpoint}, longestTimeToGetToTarget: ${longestTimeToGetToTarget}", 3)

  // Set vents proportionally
  def roomState = state.roomState
  roomState.each{roomId, stateVal -> 
    stateVal.ventIds.each {
      def vent = getChildDevice(it)
      if (vent) {
        def rate = hvacMode == COOLING ? stateVal.coolingRate : stateVal.heatingRate
        def percentageOpen = calculateVentOpenPercentange(setpoint, hvacMode, 
            rate, stateVal.lastStartTemp, longestTimeToGetToTarget)
        roomState["${roomId}"].percentOpen = percentageOpen
        def isRoomActive = vent.currentValue("room-active") == "true"
        if (settings.thermostat1CloseInactiveRooms == true && !isRoomActive) {
          def roomName = roomState["${roomId}"].roomName
          log("Closing vent on inactive room (${roomName})", 3)
          percentOpen = MINIMUM_PERCENTAGE_OPEN
        }
        patchVent(vent, percentageOpen)
      }
    }    
  }
  state.roomState = roomState
}

def checkActiveRooms() {
  def roomState = state.roomState
  if (!roomState) return
  roomState.each{roomId, stateVal -> 
    stateVal.ventIds.each {
      def vent = getChildDevice(it)
      if (vent) {
        boolean isRoomActive = vent.currentValue("room-active") == "true"
        def currPercentOpen = (vent.currentValue("percent-open")).toInteger()
        def calculatedPercentOpen = roomState["${roomId}"].percentOpen
        String roomName = roomState["${roomId}"].roomName
        if (isRoomActive && calculatedPercentOpen > currPercentOpen)  {
          log("Opening vent on active room (${roomName})", 3)
          patchVent(vent, calculatedPercentOpen)
        } else if (!isRoomActive && currPercentOpen > MINIMUM_PERCENTAGE_OPEN) {
          log("Closing vent on inactive room (${roomName})", 3)
          patchVent(vent, calculatedPercentOpen)
        }
      }
    }    
  }
}

def captureRoomTempsAndCalculateLongestMinutesToTarget(hvacMode, setpoint) {
  def longestTimeToGetToTarget = 0
  def roomState = state.roomState // workaround to force the state to update
  roomState.each{ roomId, stateVal -> 
      log("state.roomState: roomId=${roomId}, roomState=${stateVal}", 1)
      stateVal.ventIds.each {
        def vent = getChildDevice(it)  
        if (vent) {
          stateVal.lastStartTemp = vent.currentValue("room-current-temperature-c")
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
          log("state.roomState: vent=${vent}, roomTemp=${stateVal.lastStartTemp}", 3)
        }
      }
      roomState["${roomId}"] = stateVal
  }
  state.roomState = roomState
  return longestTimeToGetToTarget
}

def calculateVentOpenPercentange(setpoint, hvacMode, rate, startTemp, longestTimeToGetToTarget) {  
  def percentageOpen = MAXIMUM_PERCENTAGE_OPEN
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

def calculateRoomChangeRate(roomId, stateVal, totalMinutes) {
  log("state.roomState: roomId=${roomId}, roomState=${stateVal}", 2)
  double currentTemp = 0.0d
  def percentOpen = 0      
  stateVal.ventIds.each {
    def vent = getChildDevice(it)
    if (vent) {
      percentOpen = percentOpen + (vent.currentValue("percent-open")).toInteger()
      currentTemp = vent.currentValue("room-current-temperature-c")
    }
  }
  percentOpen = percentOpen / stateVal.ventIds.size()
  if (percentOpen <= MINIMUM_PERCENTAGE_OPEN) {
    log("Vent was opened less than ${MINIMUM_PERCENTAGE_OPEN}% (${percentOpen}), therefore it's being excluded", 3)
    return stateVal
  }

  double diffTemps = Math.abs(stateVal.lastStartTemp - currentTemp)
  if (diffTemps < 0.25) {
    log("Current temp is ${currentTemp}. Difference in temperatures is less than 0.25 degree (${diffTemps}), therefore it's being excluded", 3)
    return stateVal
  }

  double rate = diffTemps / totalMinutes
  double percentInpercent = percentOpen / 100.0d
  def rateAt100 = rate / percentInpercent

  if (state.thermostat1State.mode == COOLING) {
    stateVal.coolingRate = rateAt100
  } else {
    stateVal.heatingRate = rateAt100
  }
  return stateVal
}