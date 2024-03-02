/**
 *
 *  Copyright 2024 Jaime Botero. All Rights Reserved
 *
 *  Licensed under the Apache License, Version 2.0 (the 'License');
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an 'AS IS' BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import groovy.transform.Field

@Field static String BASE_URL = 'https://api.flair.co'
@Field static String CONTENT_TYPE = 'application/json'
@Field static String COOLING = 'cooling'
@Field static String HEATING = 'heating'
@Field static String PENDING_COOL = 'pending cool'
@Field static String PENDING_HEAT = 'pending heat'
@Field static BigDecimal MIN_PERCENTAGE_OPEN = 0.0
@Field static BigDecimal ROOM_RATE_CALC_MIN_MINUTES = 2.5
@Field static BigDecimal MAX_PERCENTAGE_OPEN = 100.0
@Field static BigDecimal MAX_MINUTES_TO_SETPOINT = 60
@Field static BigDecimal MIN_MINUTES_TO_SETPOINT = 4
@Field static BigDecimal MAX_TEMP_CHANGE_RATE_C = 2.0
@Field static BigDecimal MIN_TEMP_CHANGE_RATE_C = 0.001
@Field static BigDecimal MIN_COMBINED_VENT_FLOW_PERCENTAGE = 30.0
@Field static BigDecimal INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET = 1.5
@Field static Integer MAX_NUMBER_OF_STANDARD_VENTS = 15
@Field static Integer HTTP_TIMEOUT_SECS = 5
@Field static BigDecimal BASE_CONST = 0.00139
@Field static BigDecimal EXP_CONST = 6.58

definition(
    name: 'Flair Vents',
    namespace: 'bot.flair',
    author: 'Jaime Botero',
    description: 'Provides discovery and control capabilities for Flair Vent devices',
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
      paragraph '<b><small>Obtain your client Id and secret from ' +
        "<a href='https://forms.gle/VohiQjWNv9CAP2ASA' target='_blank'>here<a/></b></small>"
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

      section('<h2>Dynamic Airflow Balancing</h2>') {
        input 'dabEnabled', title: 'Use Dynamic Airflow Balancing', submitOnChange: true, defaultValue: false, 'bool'
        if (dabEnabled) {
          input 'thermostat1', title: 'Choose Thermostat for Vents',  multiple: false, required: true, 'capability.thermostat'
          input name: 'thermostat1TempUnit', type: 'enum', title: 'Units used by Thermostat', defaultValue: 2,
                options: [1:'Celsius (°C)', 2:'Fahrenheit (°F)']
          input 'thermostat1AdditionalStandardVents', title: 'Count of conventional Vents', submitOnChange: true, defaultValue: 0, 'number'
          paragraph '<small>Enter the total number of standard (non-Flair) adjustable vents in the home associated ' +
                'with the chosen thermostat, excluding Flair vents. This value will ensure the combined airflow across ' +
                'all vents does not drop below a specified percent. It is used to maintain adequate airflow and prevent ' +
                'potential frosting or other HVAC problems caused by lack of air movement.</small>'
          input 'thermostat1CloseInactiveRooms', title: 'Close vents on inactive rooms', submitOnChange: true, defaultValue: true, 'bool'
          if (settings.thermostat1AdditionalStandardVents < 0) {
            app.updateSetting('thermostat1AdditionalStandardVents', 0)
          } else if (settings.thermostat1AdditionalStandardVents > MAX_NUMBER_OF_STANDARD_VENTS) {
            app.updateSetting('thermostat1AdditionalStandardVents', MAX_NUMBER_OF_STANDARD_VENTS)
          }
          if (!atomicState.thermostat1Mode || atomicState.thermostat1Mode == 'auto') {
            patchStructureData(['mode': 'manual'])
            atomicState.thermostat1Mode = 'manual'
          }
        } else if (!atomicState.thermostat1Mode || atomicState.thermostat1Mode == 'manual') {
          patchStructureData(['mode': 'auto'])
          atomicState.thermostat1Mode = 'auto'
        }
        if (settings.thermostat1) {
          unsubscribe(settings.thermostat1, 'thermostatOperatingState')
          subscribe(settings.thermostat1, 'thermostatOperatingState', thermostat1ChangeStateHandler)
        }
      }
    } else {
      section {
        paragraph 'Device discovery button is hidden until authorization is completed.'
      }
    }
    section {
      input name: 'debugLevel', type: 'enum', title: 'Choose debug level', defaultValue: 0,
        options: [0:'None', 1:'Level 1 (All)', 2:'Level 2', 3:'Level 3'], submitOnChange: true
    }
  }
}

def listDiscoveredDevices() {
  def children = getChildDevices()
  def builder = new StringBuilder()
  builder << '<ul>'
  children.each {
    if (it != null) {
      builder << "<li><a href='/device/edit/${it.getId()}'>${it.getLabel()}</a></li>"
    }
  }
  builder << '</ul>'
  def links = builder.toString()
  section {
    paragraph 'Discovered devices are listed below:'
    paragraph links
  }
}

def updated() {
  log('Hubitat Flair App updating', 3)
  atomicState.remove('roomState')
}

def installed() {
  log('Hubitat Flair App installed', 3)
}

def uninstalled() {
  log('Hubitat Flair App uninstalling', 3)
  removeChildren()
  unschedule()
  unsubscribe()
}

def initialize(evt) {
  log(evt, 2)
}

// Helpers
def updateMaxRunningTime(totalMinutes) {
  if (!atomicState?.maxHvacRunningTime && totalMinutes < MAX_MINUTES_TO_SETPOINT) {
    atomicState.maxHvacRunningTime = totalMinutes
  } else if (totalMinutes >= MAX_MINUTES_TO_SETPOINT) {
    atomicState.remove('maxHvacRunningTime')
  } else if (totalMinutes > atomicState.maxHvacRunningTime) {
    atomicState.maxHvacRunningTime = totalMinutes
  }
}

def getMaxRunningTime() {
  if (!atomicState?.maxHvacRunningTime && atomicState?.maxHvacRunningTime > 0) {
    return MAX_MINUTES_TO_SETPOINT
  }
  return atomicState.maxHvacRunningTime
}

private openAllVents(ventIdsByRoomId, percentOpen) {
  ventIdsByRoomId.each { roomId, ventIds ->
    ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) { return }
      patchVent(vent, percentOpen)
    }
  }
}

private getPercentageOpen(ventIds) {
  def percentOpen = 0
  if (!ventIds || ventIds.size() == 0) { return percentOpen }
  ventIds.each {
    def vent = getChildDevice(it)
    if (vent) {
      percentOpen = percentOpen + (vent.currentValue('percent-open')).toInteger()
    }
  }
  percentOpen / ventIds.size()
}

private getRoomTemp(ventIds) {
  BigDecimal currentTemp = 0
  if (!ventIds || ventIds.size() == 0) { return currentTemp }
  ventIds.each {
    def vent = getChildDevice(it)
    if (vent) {
      currentTemp = currentTemp + vent.currentValue('room-current-temperature-c')
    }
  }
  currentTemp / ventIds.size()
}

private atomicStateUpdate(stateKey, key, value) {
  atomicState.updateMapValue(stateKey, key, value)
  log("atomicStateUpdate(${stateKey}, ${key}, ${value}", 1)
}

private getTempOfColdestAndHottestRooms() {
  def tempColdestRoom = 0
  def tempHottestRoom = 0
  def ventIdsByRoomId = atomicState.ventsByRoomId
  ventIdsByRoomId.each { roomId, ventIds ->
    ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) { return }
      def lastStartTemp = vent.currentValue('room-current-temperature-c')
      sendEvent(vent, [name: 'room-starting-temperature-c', value: lastStartTemp])
      if (tempHottestRoom < lastStartTemp) {
        tempHottestRoom = lastStartTemp
      }
      if (tempColdestRoom == 0 || tempColdestRoom > lastStartTemp) {
        tempColdestRoom = lastStartTemp
      }
    }
  }
  [tempColdestRoom: tempColdestRoom, tempHottestRoom: tempHottestRoom]
}

def getThermostatSetpoint(hvacMode) {
  BigDecimal setpoint = hvacMode == COOLING ?
     thermostat1.currentValue('coolingSetpoint') :
     thermostat1.currentValue('heatingSetpoint')
  setpoint = setpoint ?: thermostat1.currentValue('thermostatSetpoint')
  if (!setpoint) {
    log.error('Thermostat has no setpoint property, please choose a vaid thermostat')
    return setpoint
  }
  if (settings.thermostat1TempUnit == '2') {
    setpoint =  convertFahrenheitToCentigrades(setpoint)
  }
  return setpoint
}

def roundBigDecimal(BigDecimal number, decimalPoints = 3) {
  number.setScale(decimalPoints, BigDecimal.ROUND_HALF_UP)
}

def convertFahrenheitToCentigrades(tempValue) {
  (tempValue - 32) * (5 / 9)
}

def roundToNearestFifth(BigDecimal num) {
  Math.round(num / 5) * 5
}

def rollingAverage(BigDecimal currentAverage, BigDecimal newNumber, BigDecimal weight = 1, int numEntries = 10) {
  if (numEntries <= 0) { return 0 }
  BigDecimal rollingAverage = !currentAverage || currentAverage == 0 ? newNumber : currentAverage
  BigDecimal sum = rollingAverage * (numEntries - 1)
  def weightedValue = (newNumber - rollingAverage) * weight
  def numberToAdd = rollingAverage + weightedValue
  sum += numberToAdd
  return sum / numEntries
}

def hasRoomReachedSetpoint(hvacMode, setpoint, currentVentTemp) {
  (hvacMode == COOLING && setpoint >= currentVentTemp) || (hvacMode == HEATING && setpoint <= currentVentTemp)
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
    log.error('HTTP Null response')
    return false
   } else if (resp.hasError()) {
    def respCode = resp?.getStatus() ? resp.getStatus() : ''
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
  def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}" +
    '&scope=vents.view+vents.edit+structures.view+structures.edit&grant_type=client_credentials'
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
  if (!isValidResponse(resp)) { return }
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
  if (!isValidResponse(resp)) { return }
  processRoomTraits(data.device, resp.getJson())
}

def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp)) { return }
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

  if (!details.data) { return }
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

  if (!details?.data) { return }
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
    uri = BASE_URL + '/api/remote-sensors/' + remoteSensor.id + '/sensor-readings'
    getDataAsync(uri, handleRemoteSensorGet, [device: device])
  }

  updateByRoomIdState(details)
}

def handleRemoteSensorGet(resp, data) {
  if (!isValidResponse(resp)) { return }
  def details = resp?.getJson()
  def propValue = details?.data?.first()?.attributes['occupied']
  //log("handleRemoteSensorGet: ${details}", 1)
  sendEvent(data.device, [name: 'room-occupied', value: propValue])
}

def updateByRoomIdState(details) {
  if (!details?.data?.relationships?.vents?.data) { return }
  def roomId = details.data.id
  if (!atomicState.ventsByRoomId?."${roomId}") {
    def ventIds = []
    details.data.relationships.vents.data.each {
      ventIds.add(it.id)
    }
    atomicStateUpdate('ventsByRoomId', roomId, ventIds)
  }
  //log(atomicState.ventsByRoomId, 1)
}

 // ### Operations ###

def patchStructureData(attributes) {
  if (!state.structureId) { return }
  def body = [data: [type: 'structures', attributes: attributes]]
  def uri = BASE_URL + "/api/structures/${state.structureId}"
  patchDataAsync(uri, null, body)
}

def getStructureData() {
  def uri = BASE_URL + '/api/structures'
  getDataAsync(uri, handleStructureGet)
}

def handleStructureGet(resp, data) {
  if (!isValidResponse(resp)) { return }
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
  def pOpen = percentOpen
  if (pOpen > 100) {
    pOpen = 100
  } else if (pOpen  < 0) {
    pOpen = 0
  }
  def deviceId = device.getDeviceNetworkId()
  def uri = BASE_URL + '/api/vents/' + deviceId
  def body = [
    data: [
      type: 'vents',
      attributes: [
        'percent-open': (pOpen).toInteger()
      ]
    ]
  ]
  patchDataAsync(uri, handleVentPatch, body, [device: device])
  sendEvent(device, [name: 'percent-open', value: pOpen])
}

def handleVentPatch(resp, data) {
  if (!isValidResponse(resp)) { return }
  traitExtract(data.device, resp.getJson(), 'percent-open', '%')
}

def patchRoom(device, active) {
  def roomId = device.currentValue('room-id')
  if (!roomId || active == null) { return }
  def isRoomActive = device.currentValue('room-active')
  if (active == isRoomActive) { return }
  def roomName = device.currentValue('room-name')
  log("Setting active state to ${active} for '${roomName}'", 3)

  def uri = BASE_URL + '/api/rooms/' + roomId
  def body = [
    data: [
      type: 'rooms',
      attributes: [
        'active' : active == 'true'
      ]
    ]
  ]
  patchDataAsync(uri, handleRoomPatch, body, [device: device])
}

def handleRoomPatch(resp, data) {
  if (!isValidResponse(resp)) { return }
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
  switch (hvacMode) {
     case COOLING:
     case HEATING:
      if (atomicState.thermostat1State) {
        log("initializeRoomStates has already been executed (${evt.value})", 3)
        return
      }
      atomicStateUpdate('thermostat1State', 'mode', hvacMode)
      atomicStateUpdate('thermostat1State', 'startTime', now())
      unschedule(initializeRoomStates)
      unschedule(checkActiveRooms)
      runInMillis(1000, 'initializeRoomStates', [data: hvacMode]) // wait a bit since setpoint is set a few ms later
      if (settings.thermostat1CloseInactiveRooms == true) {
        runEvery5Minutes('checkActiveRooms')
      }
      break
     default:
      unschedule(checkActiveRooms)
      if (atomicState.thermostat1State)  {
        unschedule(finalizeRoomStates)
        atomicStateUpdate('thermostat1State', 'endTime', now())
        def params = [
            ventIdsByRoomId: atomicState.ventsByRoomId,
            startTime: atomicState.thermostat1State?.startTime,
            endTime: atomicState.thermostat1State?.endTime,
            hvacMode: atomicState.thermostat1State?.mode
        ]
        // Run a minute after to get more accurate temp readings
        runInMillis(1000 * 60, 'finalizeRoomStates', [data: params])
      }
      break
  }
}

// ### Dynamic Airflow Balancing ###
def finalizeRoomStates(data) {
  if (!data.ventIdsByRoomId || !data.startTime || !data.endTime || !data.hvacMode) {
    log.warn('Finalizing room states: wrong parameters')
    atomicState.remove('thermostat1State')
    return
  }
  log('Finalizing room states', 3)
  def totalMinutes = (data.endTime - data.startTime) / (1000 * 60)
  log("HVAC ran for ${totalMinutes} minutes", 3)
  updateMaxRunningTime(totalMinutes)
  if (totalMinutes >= ROOM_RATE_CALC_MIN_MINUTES) {
    data.ventIdsByRoomId.each { roomId, ventIds ->
      ventIds.each {
        def vent = getChildDevice(it)
        if (!vent) { return }
        def percentOpen = getPercentageOpen(ventIds)
        BigDecimal currentTemp = getRoomTemp(ventIds)
        BigDecimal lastStartTemp = vent.currentValue('room-starting-temperature-c')
        def newRate = calculateRoomChangeRate(lastStartTemp, currentTemp, totalMinutes, percentOpen)
        if (newRate < 0) { return }
        def ratePropName = hvacMode == COOLING ? 'room-cooling-rate' : 'room-heating-rate'
        def currentRate = vent.currentValue(ratePropName)
        def rate = rollingAverage(currentRate, newRate, percentOpen / 100)
        sendEvent(vent, [name: ratePropName, value: rate])
        // def roomName = vent.currentValue('room-name')
        // log("'${roomName}': currentRate: ${roundBigDecimal(currentRate)}, rollingRate: ${roundBigDecimal(rate)}", 3)
      }
    }
  }
  atomicState.remove('thermostat1State')
}

def initializeRoomStates(hvacMode) {
  if (!dabEnabled) { return }
  log("Initializing room states - hvac mode: ${hvacMode})", 3)
  if (!atomicState.ventsByRoomId) { return }
  // Get the target temperature from the thermostat
  BigDecimal setpoint = getThermostatSetpoint(hvacMode)
  if (!setpoint) { return }
  def tempData = getTempOfColdestAndHottestRooms()
  if (hvacMode == COOLING && tempData.tempColdestRoom < setpoint) {
    setpoint = tempData.tempColdestRoom
    log("Setting setpoint to coldest room at ${setpoint}C", 3)
  } else if (hvacMode == HEATING && tempData.tempHottestRoom > setpoint) {
    setpoint = tempData.tempHottestRoom
    log("Setting setpoint to hottest room at ${setpoint}C", 3)
  }

  def rateAndTempPerVentId = getAttribsPerVentId(atomicState.ventsByRoomId)

  // Get longest time to reach to target temp
  def longestTimeToGetToTarget = calculateLongestMinutesToTarget(
    rateAndTempPerVentId, hvacMode, setpoint, getMaxRunningTime())
  if (longestTimeToGetToTarget <= 0) {
    log("Openning all vents (setpoint: ${setpoint})", 3)
    openAllVents(atomicState.ventsByRoomId, MAX_PERCENTAGE_OPEN)
    return
  }
  log("Initializing room states - setpoint: ${setpoint}, longestTimeToGetToTarget: ${longestTimeToGetToTarget}", 3)

  // Calculate percent open for each vent vents proportionally
  //log("rateAndTempPerVentId: ${rateAndTempPerVentId}", 3)
  def calculatedPercentOpenPerVentId = calculateOpenPercentageForAllVents(
    rateAndTempPerVentId, hvacMode, setpoint, longestTimeToGetToTarget,
    settings.thermostat1CloseInactiveRooms)

  // Ensure mimimum combined vent flow across vents
  calculatedPercentOpenPerVentId = adjustVentOpeningsToEnsureMinimumAirflowTarget(
    calculatedPercentOpenPerVentId, settings.thermostat1AdditionalStandardVents)

  // Apply open percentage across all vents
  calculatedPercentOpenPerVentId.each { ventId, percentOpen ->
    def vent = getChildDevice(ventId)
    if (vent) {
      patchVent(vent, roundToNearestFifth(percentOpen))
    }
  }
}

def adjustVentOpeningsToEnsureMinimumAirflowTarget(calculatedPercentOpenPerVentId, additionalStandardVents) {
  def totalDeviceCount = additionalStandardVents > 0 ? additionalStandardVents : 0
  def sumPercentages = totalDeviceCount * 50 // Assuming all standard vents are at 50%
  calculatedPercentOpenPerVentId.each { ventId, percentOpen ->
    totalDeviceCount++
    if (percentOpen) {
        sumPercentages += percentOpen
    }
  }
  if (totalDeviceCount <= 0) {
    log.warn('totalDeviceCount is zero')
    return calculatedPercentOpenPerVentId
  }
  def combinedVentFlowPercentage = (100 * sumPercentages) / (totalDeviceCount * 100)
  if (combinedVentFlowPercentage >= MIN_COMBINED_VENT_FLOW_PERCENTAGE) {
    log("Combined vent flow percentage (${combinedVentFlowPercentage}) is greather than ${MIN_COMBINED_VENT_FLOW_PERCENTAGE}", 3)
    return calculatedPercentOpenPerVentId
  }
  log("Combined Vent Flow Percentage (${combinedVentFlowPercentage}) is lower than ${MIN_COMBINED_VENT_FLOW_PERCENTAGE}%", 3)
  def targetPercentSum = MIN_COMBINED_VENT_FLOW_PERCENTAGE * totalDeviceCount
  def diffPercentageSum = targetPercentSum - sumPercentages
  log("sumPercentages=${sumPercentages}, targetPercentSum=${targetPercentSum}, diffPercentageSum=${diffPercentageSum}", 2)
  def continueAdjustments = true
  while (diffPercentageSum > 0 && continueAdjustments) {
    continueAdjustments = false
    calculatedPercentOpenPerVentId.each { ventId, percentOpen ->
      def percentOpenVal = percentOpen ?: 0
      if (percentOpenVal < MAX_PERCENTAGE_OPEN) {
        def increment = INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET * ((percentOpenVal > 0 ? percentOpenVal : 1) / 100)
        percentOpenVal = roundBigDecimal(percentOpenVal + increment)
        calculatedPercentOpenPerVentId[ventId] = percentOpenVal
        diffPercentageSum = diffPercentageSum - increment
        continueAdjustments = true
        log("Adjusting % open from ${percentOpenVal - increment}% to ${percentOpenVal}%", 2)
      }
    }
  }
  return calculatedPercentOpenPerVentId
}

def getAttribsPerVentId(ventIdsByRoomId) {
  def rateAndTempPerVentId = [:]
  ventIdsByRoomId.each { roomId, ventIds ->
    ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) { return }
      def rate = hvacMode == COOLING ?
         vent.currentValue('room-cooling-rate') :
         vent.currentValue('room-heating-rate')
      rate = rate ?: 0
      def isRoomActive = vent.currentValue('room-active') == 'true'
      rateAndTempPerVentId."${it}" = [
        'rate':  rate,
        'temp': vent.currentValue('room-current-temperature-c'),
        'active': isRoomActive,
        'name': vent.currentValue('room-name')
      ]
    }
  }
  return rateAndTempPerVentId
}

def calculateOpenPercentageForAllVents(rateAndTempPerVentId,
   hvacMode, setpoint, longestTimeToGetToTarget, closeInactiveRooms = true) {
  def calculatedPercentOpenPerVentId = [:]
  rateAndTempPerVentId.each { ventId, stateVal ->
    def percentageOpen = MIN_PERCENTAGE_OPEN
    if (closeInactiveRooms == true && !stateVal.active) {
      log('Closing vent on inactive room', 3)
    } else if (stateVal.rate < MIN_TEMP_CHANGE_RATE_C) {
      percentageOpen = MAX_PERCENTAGE_OPEN
    } else {
      percentageOpen = calculateVentOpenPercentange(stateVal.temp, setpoint, hvacMode,
        stateVal.rate, longestTimeToGetToTarget)
    }
    calculatedPercentOpenPerVentId."${ventId}" = percentageOpen
  }
  return calculatedPercentOpenPerVentId
}

def calculateVentOpenPercentange(startTemp, setpoint, hvacMode, maxRate, longestTimeToGetToTarget) {
  if (hasRoomReachedSetpoint(hvacMode, setpoint, startTemp)) {
    log("Room is already warmer/cooler (${startTemp}) than setpoint (${setpoint})", 3)
    return MIN_PERCENTAGE_OPEN
  }
  def percentageOpen = MAX_PERCENTAGE_OPEN
  if (maxRate > 0 && longestTimeToGetToTarget > 0) {
    def targetRate = Math.abs(setpoint - startTemp) / longestTimeToGetToTarget
    percentageOpen = BASE_CONST * Math.exp((-targetRate * Math.log(BASE_CONST)) / maxRate)
    percentageOpen = roundBigDecimal(percentageOpen * 100)
    log("percentageOpen: (${percentageOpen})", 1)
    if (percentageOpen < MIN_PERCENTAGE_OPEN) {
      percentageOpen = MIN_PERCENTAGE_OPEN
    } else if (percentageOpen > MAX_PERCENTAGE_OPEN) {
      percentageOpen = MAX_PERCENTAGE_OPEN
    }
  }
  return percentageOpen
}

def checkActiveRooms() {
  if (!atomicState.ventsByRoomId) { return }
  atomicState.ventsByRoomId.each { roomId, ventIds ->
    ventIds.each {
      def vent = getChildDevice(it)
      if (!vent) { return }
      boolean isRoomActive = vent.currentValue('room-active') == 'true'
      def currPercentOpen = (vent.currentValue('percent-open')).toInteger()
      if (settings.thermostat1CloseInactiveRooms == true &&
         !isRoomActive && currPercentOpen > MIN_PERCENTAGE_OPEN) {
        String roomName = vent.currentValue('room-name')
        log("Closing vent on inactive room (${roomName})", 3)
        patchVent(vent, MIN_PERCENTAGE_OPEN)
      }
    }
  }
}

def calculateLongestMinutesToTarget(rateAndTempPerVentId, hvacMode, setpoint, maxRunningTime) {
  def longestTimeToGetToTarget = 0
  rateAndTempPerVentId.each { ventId, stateVal ->
    def minutesToTarget = 0
    def rate = stateVal.rate
    if (!hasRoomReachedSetpoint(hvacMode, setpoint, stateVal.temp) && rate > 0) {
      minutesToTarget = Math.abs(setpoint - stateVal.temp) / rate
    }
    if (minutesToTarget > maxRunningTime) {
      log.warn("Room '${stateVal.name}' is taking ${minutesToTarget} minutes, " +
        "which is longer than the limit of ${maxRunningTime} minutes")
      minutesToTarget = maxRunningTime
    } else if (minutesToTarget < 0) {
      minutesToTarget = 0
    }
    if (longestTimeToGetToTarget < minutesToTarget) {
      longestTimeToGetToTarget = minutesToTarget
    }
    log("atomicState.ventsByRoomId: name=${stateVal.name}, roomTemp=${stateVal.temp}", 3)
  }
  return longestTimeToGetToTarget
}

def calculateRoomChangeRate(lastStartTemp, currentTemp, totalMinutes, percentOpen) {
  if (totalMinutes < MIN_MINUTES_TO_SETPOINT) {
    log('Insuficient number of minutes required to calculate change rate ' +
      "(${totalMinutes} should be greather than ${MIN_MINUTES_TO_SETPOINT})", 3)
    return -1
  }
  if (percentOpen <= MIN_PERCENTAGE_OPEN) {
    log("Vent was opened less than ${MIN_PERCENTAGE_OPEN}% (${percentOpen}), therefore it is being excluded", 3)
    return -1
  }
  BigDecimal diffTemps = Math.abs(lastStartTemp - currentTemp)
  BigDecimal rate = diffTemps / totalMinutes

  BigDecimal pOpen = percentOpen / 100

  BigDecimal equivalentExpRate = (Math.log(pOpen / BASE_CONST)) / EXP_CONST
  BigDecimal approxMaxRate = (Math.log(1 / BASE_CONST)) / EXP_CONST
  BigDecimal approxEquivMaxRate = (approxMaxRate * rate) / equivalentExpRate

  if (approxEquivMaxRate > MAX_TEMP_CHANGE_RATE_C) {
    def roundedRate = roundBigDecimal(approxEquivMaxRate)
    log("Change rate (${roundedRate}) is greater than ${MAX_TEMP_CHANGE_RATE_C}, therefore it is being excluded", 3)
    return -1
  } else if (approxEquivMaxRate < MIN_TEMP_CHANGE_RATE_C) {
    def roundedRate = roundBigDecimal(approxEquivMaxRate)
    log("Change rate (${roundedRate}) is lower than ${MIN_TEMP_CHANGE_RATE_C}, therefore it is being excluded", 3)
    return -1
  }
  return approxEquivMaxRate
}

