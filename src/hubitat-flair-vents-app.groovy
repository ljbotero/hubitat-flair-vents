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
@Field static Integer MILLIS_DELAY_TEMP_READINGS = 1000 * 30
@Field static BigDecimal MIN_PERCENTAGE_OPEN = 0.0
@Field static BigDecimal VENT_PRE_ADJUSTMENT_THRESHOLD_C = 0.2
@Field static BigDecimal ROOM_RATE_CALC_MIN_MINUTES = 2.5
@Field static BigDecimal MAX_PERCENTAGE_OPEN = 100.0
@Field static BigDecimal MAX_MINUTES_TO_SETPOINT = 60
@Field static BigDecimal MIN_MINUTES_TO_SETPOINT = 5
@Field static BigDecimal SETPOINT_OFFSET_C = 0.7
@Field static BigDecimal MAX_TEMP_CHANGE_RATE_C = 1.5
@Field static BigDecimal MIN_TEMP_CHANGE_RATE_C = 0.001
@Field static BigDecimal MIN_COMBINED_VENT_FLOW_PERCENTAGE = 30.0
@Field static BigDecimal INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET = 1.5
@Field static Integer MAX_NUMBER_OF_STANDARD_VENTS = 15
@Field static Integer MAX_ITERATIONS = 500
@Field static Integer HTTP_TIMEOUT_SECS = 5
@Field static BigDecimal BASE_CONST = 0.0991
@Field static BigDecimal EXP_CONST = 2.3

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
      if (settings?.clientId != null && settings?.clientSecret != null ) {
        input 'authenticate', 'button', title: 'Authenticate', submitOnChange: true
      }

      if (state.authError) {
        section {
          paragraph "<span style='color: red;'>${state.authError}</span>"
        }
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
        for (child in getChildDevices()) {
          input "thermostat${child.getId()}", 
            title: "Choose Thermostat for ${child.getLabel()} (Optional)",
            multiple: false, required: false, 'capability.temperatureMeasurement'
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
  final String acBoosterLink = 'https://amzn.to/3QwVGbs'
  def children = getChildDevices()
  BigDecimal maxCoolEfficiency = 0
  BigDecimal maxHeatEfficiency = 0
  for (vent in children) {
    def coolingRate = vent.currentValue('room-cooling-rate')
    def heatingRate = vent.currentValue('room-heating-rate')
    if (maxCoolEfficiency < coolingRate) {
      maxCoolEfficiency = coolingRate
    }
    if (maxHeatEfficiency < heatingRate) {
      maxHeatEfficiency = heatingRate
    }
  }

  def builder = new StringBuilder()
  builder << '<style>' +
      '.device-table { width: 100%; border-collapse: collapse; font-family: Arial, sans-serif; color: black; }' +
      '.device-table th, .device-table td { padding: 8px; text-align: left; border-bottom: 1px solid #ddd; }' +
      '.device-table th { background-color: #f2f2f2; color: #333; }' +
      '.device-table tr:hover { background-color: #f5f5f5; }' +
      '.device-table a { color: #333; text-decoration: none; }' +
      '.device-table a:hover { color: #666; }' +
      '.device-table th:not(:first-child), .device-table td:not(:first-child) { text-align: center; }' +
      '.warning-message { color: darkorange; cursor: pointer; }' +
      '.danger-message { color: red; cursor: pointer; }' +
    '</style>' +
    '<table class="device-table">' +
      '<thead>' +
      '  <tr>' +
      '    <th></th>' +
      '    <th colspan="2">Efficiency</th>' +
      '  </tr>' +
      '  <tr>' +
      '    <th rowspan="2">Device</th>' +
      '    <th>Cooling</th>' +
      '    <th>Heating</th>' +
      '  </tr>' +
      '</thead>' +
      '<tbody>'
  children.each {
    if (it != null) {
      def coolingRate = it.currentValue('room-cooling-rate')
      def heatingRate = it.currentValue('room-heating-rate')
      def coolEfficiency = maxCoolEfficiency > 0 ? roundBigDecimal((coolingRate / maxCoolEfficiency) * 100, 0) : 0
      def heatEfficiency = maxHeatEfficiency > 0 ? roundBigDecimal((heatingRate / maxHeatEfficiency) * 100, 0) : 0

      def coolClass = coolEfficiency <= 0 ? '' : coolEfficiency <= 25 ? 'danger-message' : coolEfficiency <= 45 ? 'warning-message' : ''
      def heatClass = heatEfficiency <= 0 ? '' : heatEfficiency <= 25 ? 'danger-message' : heatEfficiency <= 45 ? 'warning-message' : ''      
      def warnMsg = 'This vent is very inefficient, consider installing an HVAC booster. Click for a recommendation.'

      def coolPopupHtml = coolEfficiency <= 45 ?
        "<span class='${coolClass}' onclick=\"window.open('${acBoosterLink}');\" title='${warnMsg}'>${coolEfficiency}%</span>" : "${coolEfficiency}%"
      def heatPopupHtml = heatEfficiency <= 45 ?
        "<span class='${heatClass}' onclick=\"window.open('${acBoosterLink}');\" title='${warnMsg}'>${heatEfficiency}%</span>" : "${heatEfficiency}%"

      builder << "<tr><td><a href='/device/edit/${it.getId()}'>${it.getLabel()}</a>" +
        "</td><td>${coolPopupHtml}</td><td>${heatPopupHtml}</td></tr>"
    }
  }
  builder << '</table>'
  def links = builder.toString()
  section {
    paragraph 'Discovered devices:'
    paragraph links
  }
}

def updated() {
  log.debug('Hubitat Flair App updating')
  initialize()
}

def installed() {
  log.debug('Hubitat Flair App installed')
  initialize()
}

def uninstalled() {
  log.debug('Hubitat Flair App uninstalling')
  removeChildren()
  unschedule()
  unsubscribe()
}

def initialize() {
  unsubscribe()
  if (settings.thermostat1) {
    subscribe(settings.thermostat1, 'thermostatOperatingState', thermostat1ChangeStateHandler)
    subscribe(settings.thermostat1, 'temperature', thermostat1ChangeTemp)

    def temp = thermostat1.currentValue('temperature')
    def coolingSetpoint = thermostat1.currentValue('coolingSetpoint')
    def heatingSetpoint = thermostat1.currentValue('heatingSetpoint')
    String hvacMode = calculateHvacMode(temp, coolingSetpoint, heatingSetpoint)
    runInMillis(3000, 'initializeRoomStates', [data: hvacMode])
  }
}

// Helpers

private openAllVents(ventIdsByRoomId, percentOpen) {
  ventIdsByRoomId.each { roomId, ventIds ->
    ventIds.each {
      def vent = getChildDevice(it)
      if (vent) {
        patchVent(vent, percentOpen)
      }
    }
  }
}

private getRoomTemp(vent) {
  def tempDevice = settings."thermostat${vent.getId()}"
  if (tempDevice) {
    def temp = tempDevice.currentValue('temperature')
    if (settings.thermostat1TempUnit == '2') {
      temp =  convertFahrenheitToCentigrades(temp)
    }
    log("Got temp from ${tempDevice.getLabel()} of ${temp}", 2)
    return temp
  }
  return vent.currentValue('room-current-temperature-c')
}

private atomicStateUpdate(stateKey, key, value) {
  atomicState.updateMapValue(stateKey, key, value)
  log("atomicStateUpdate(${stateKey}, ${key}, ${value}", 1)
}

def getThermostatSetpoint(hvacMode) {
  BigDecimal setpoint = hvacMode == COOLING ?
     thermostat1.currentValue('coolingSetpoint') - SETPOINT_OFFSET_C :
     thermostat1.currentValue('heatingSetpoint') + SETPOINT_OFFSET_C
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

def hasRoomReachedSetpoint(hvacMode, setpoint, currentVentTemp, offset = 0) {
  (hvacMode == COOLING && currentVentTemp <= setpoint - offset) ||
  (hvacMode == HEATING && currentVentTemp >= setpoint + offset)
}

def calculateHvacMode(temp, coolingSetpoint, heatingSetpoint) {
  Math.abs(temp - coolingSetpoint) < Math.abs(temp - heatingSetpoint) ?
    COOLING : HEATING
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

private logDetails(msg, details = null, level = 3) {
  def settingsLevel = (settings?.debugLevel).toInteger()
  if (!details || (settingsLevel == 3 && level >= 2)) {
    log(msg, level)
  } else {
    log("${msg}\n${details}", level)
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
  }
  try {
    if (resp.hasError()) {
      def respCode = resp?.getStatus() ? resp.getStatus() : ''
      def respError = resp?.getErrorMessage() ? resp.getErrorMessage() : resp
      log.error("HTTP response code: ${respCode}, body: ${respError}")
      return false
    }
  } catch (err) {
    log.error(err)
  }
  return true
}

def getData(uri, handler, data = null) {
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = CONTENT_TYPE
  def httpParams = [
    uri: uri, headers: headers, contentType: contentType,
    timeout: HTTP_TIMEOUT_SECS,
    query: data
  ]
  try {
    httpGet(httpParams) { resp ->
      if (resp.success) {
        handler(resp, resp.data)
      }
    }
  } catch (e) {
    log.warn "httpGet call failed: ${e.message}"
  }
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
  logDetails("patchDataAsync: ${uri}", "body:${body}", 2)
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
    case 'authenticate':
      login()
      unschedule(login)
      runEvery1Hour login
      break
    case 'discoverDevices':
      discover()
      break
  }
}

private void discover() {
  log('Discovery started', 3)
  atomicState.remove('ventsByRoomId')
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
  if (!isValidResponse(resp) || !data || !data?.device) { return }
  processRoomTraits(data.device, resp.getJson())
}

def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp) || !data || !data?.device) { return }
  processVentTraits(data.device, resp.getJson())
}

def traitExtract(device, details, propNameData, propNameDriver = propNameData, unit = null) {
  try {
    def propValue = details.data.attributes[propNameData]
    if (propValue != null) {
      if (unit) {
        sendEvent(device, [name: propNameDriver, value: propValue, unit: unit])
      } else {
        sendEvent(device, [name: propNameDriver, value: propValue])
      }
    }
  } catch (err) {
    log.warn(err)
  }
}

def processVentTraits(device, details) {
  logDetails("Processing Vent data for ${device}", details, 1)

  if (!details || details?.data) { return }
  traitExtract(device, details, 'firmware-version-s')
  traitExtract(device, details, 'rssi')
  traitExtract(device, details, 'connected-gateway-puck-id')
  traitExtract(device, details, 'created-at')
  traitExtract(device, details, 'duct-pressure')
  traitExtract(device, details, 'percent-open', 'percent-open', '%')
  traitExtract(device, details, 'percent-open', 'level', '%')
  traitExtract(device, details, 'duct-temperature-c')
  traitExtract(device, details, 'motor-run-time')
  traitExtract(device, details, 'system-voltage')
  traitExtract(device, details, 'motor-current')
  traitExtract(device, details, 'has-buzzed')
  traitExtract(device, details, 'updated-at')
  traitExtract(device, details, 'inactive')
}

def processRoomTraits(device, details) {
  if (!device || !details || !details?.data || !details?.data?.id) { return }

  logDetails("Processing Room data for ${device}", details, 1)
  def roomId = details?.data?.id
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
  if (details?.data?.relationships['remote-sensors'] 
      && details?.data?.relationships['remote-sensors']?.data) {
      def remoteSensor = details?.data?.relationships['remote-sensors']?.data?.first()
      if (remoteSensor) {
        uri = BASE_URL + '/api/remote-sensors/' + remoteSensor.id + '/sensor-readings'
        getDataAsync(uri, handleRemoteSensorGet, [device: device])
      }
  }

  updateByRoomIdState(details)
}

def handleRemoteSensorGet(resp, data) {
  if (!isValidResponse(resp) || !data) { return }
  try {
      def details = resp?.getJson()
      def propValue = details?.data?.first()?.attributes['occupied']
      //log("handleRemoteSensorGet: ${details}", 1)
      sendEvent(data.device, [name: 'room-occupied', value: propValue])
  } (err) {
    log.error(err)
  }
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
  def pOpen = percentOpen
  if (pOpen > 100) {
    pOpen = 100
    log.warn('Trying to set vent open percentage to inavlid value')
  } else if (pOpen  < 0) {
    pOpen = 0
    log.warn('Trying to set vent open percentage to inavlid value')
  }
  def currPercentOpen = (device.currentValue('percent-open')).toInteger()
  if (percentOpen == currPercentOpen) {
    log("Keeping percent open for ${device} unchanged to ${percentOpen}%", 3)
    return
  }
  log("Setting percent open for ${device} from ${currPercentOpen} to ${percentOpen}%", 3)

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
  if (!isValidResponse(resp) || !data) { return }
  traitExtract(data.device, resp.getJson(), 'percent-open', '%')
  traitExtract(data.device, resp.getJson(), 'percent-open', 'level', '%')
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
  if (!isValidResponse(resp) || !data) { return }
  traitExtract(data.device, resp.getJson(), 'active', 'room-active')
}

def thermostat1ChangeTemp(evt) {
  log("thermostat changed temp to:${evt.value}", 2)
  def temp = thermostat1.currentValue('temperature')
  def coolingSetpoint = thermostat1.currentValue('coolingSetpoint')
  def heatingSetpoint = thermostat1.currentValue('heatingSetpoint')
  String hvacMode = calculateHvacMode(temp, coolingSetpoint, heatingSetpoint)
  def thermostatSetpoint = getThermostatSetpoint(hvacMode)
  if (isThermostatAboutToChangeState(hvacMode, thermostatSetpoint, temp)) {
    runInMillis(3000, 'initializeRoomStates', [data: hvacMode])
  }
}

def isThermostatAboutToChangeState(hvacMode, setpoint, temp) {
  if (hvacMode == COOLING && temp + SETPOINT_OFFSET_C - VENT_PRE_ADJUSTMENT_THRESHOLD_C < setpoint) {
    atomicState.tempDiffsInsideThreshold = false
    return false
  } else  if (hvacMode == HEATING && temp - SETPOINT_OFFSET_C + VENT_PRE_ADJUSTMENT_THRESHOLD_C > setpoint) {
    atomicState.tempDiffsInsideThreshold = false
    return false
  }
  if (atomicState.tempDiffsInsideThreshold == true) {
    return false
  }
  atomicState.tempDiffsInsideThreshold = true
  log('Pre-adjusting vents for upcoming HVAC start. ' +
    "[mode=${hvacMode}, setpoint=${setpoint}, temp=${temp}]", 3)
  return true
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
      atomicStateUpdate('thermostat1State', 'startedRunning', now())
      unschedule(initializeRoomStates)
      runInMillis(1000, 'initializeRoomStates', [data: hvacMode]) // wait a bit since setpoint is set a few ms later
      recordStartingTemperatures()
      runEvery5Minutes('evaluateRebalancingVents')
      runEvery30Minutes('reBalanceVents')
      break
     default:
      unschedule(initializeRoomStates)
      unschedule(finalizeRoomStates)
      unschedule(evaluateRebalancingVents)
      unschedule(reBalanceVents)
      if (atomicState.thermostat1State)  {
        atomicStateUpdate('thermostat1State', 'finishedRunning', now())
        def params = [
            ventIdsByRoomId: atomicState.ventsByRoomId,
            startedCycle: atomicState.thermostat1State?.startedCycle,
            startedRunning: atomicState.thermostat1State?.startedRunning,
            finishedRunning: atomicState.thermostat1State?.finishedRunning,
            hvacMode: atomicState.thermostat1State?.mode
        ]
        // Run a minute after to get more accurate temp readings
        runInMillis(MILLIS_DELAY_TEMP_READINGS, 'finalizeRoomStates', [data: params])
        atomicState.remove('thermostat1State')
      }
      break
  }
}

// ### Dynamic Airflow Balancing ###
def reBalanceVents() {
  log('Rebalancing Vents!!!', 3)
  def params = [
    ventIdsByRoomId: atomicState.ventsByRoomId,
    startedCycle: atomicState.thermostat1State?.startedCycle,
    startedRunning: atomicState.thermostat1State?.startedRunning,
    finishedRunning: now(),
    hvacMode: atomicState.thermostat1State?.mode
  ]
  finalizeRoomStates(params)
  initializeRoomStates(atomicState.thermostat1State?.mode)
}

def evaluateRebalancingVents() {
  if (!atomicState.thermostat1State) { return }

  def ventIdsByRoomId = atomicState.ventsByRoomId
  String hvacMode = atomicState.thermostat1State?.mode
  def setPoint = getThermostatSetpoint(hvacMode)

  ventIdsByRoomId.each { roomId, ventIds ->
    for (ventId in ventIds) {
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { continue }
        def isRoomActive = vent.currentValue('room-active') == 'true'
        if (!isRoomActive) { continue }
        def currPercentOpen = (vent.currentValue('percent-open')).toInteger()
        if (currPercentOpen < 90) { continue }
        def roomTemp = getRoomTemp(vent)
        def roomName = vent.currentValue('room-name')
        if (!hasRoomReachedSetpoint(hvacMode, setPoint, roomTemp, 0.5)) {
          log("Rebalancing Vents: Skipped as `${roomName}` hasn't reached setpoint", 3)
          continue
        }
        log("Rebalancing Vents - '${roomName}' is at ${roomTemp} degrees, and has passed the ${setPoint} temp target", 3)
        reBalanceVents()
        break
      } catch (err) {
        log.error(err)
      }
    }
  }
}

def finalizeRoomStates(data) {
  if (!data.ventIdsByRoomId || !data.startedCycle || !data.startedRunning || !data.finishedRunning || !data.hvacMode) {
    log.warn('Finalizing room states: wrong parameters')
    return
  }
  log('Finalizing room states', 3)
  def totalRunningMinutes = (data.finishedRunning - data.startedRunning) / (1000 * 60)
  def totalCycleMinutes = (data.finishedRunning - data.startedCycle) / (1000 * 60)
  log("HVAC ran for ${totalRunningMinutes} minutes", 3)
  atomicState.maxHvacRunningTime = roundBigDecimal(rollingAverage(atomicState.maxHvacRunningTime, totalRunningMinutes), 6)
  if (totalCycleMinutes >= ROOM_RATE_CALC_MIN_MINUTES) {
    data.ventIdsByRoomId.each { roomId, ventIds ->
      for (ventId in ventIds) {
        try {
          def vent = getChildDevice(ventId)
          if (!vent) { break }
          def percentOpen = (vent.currentValue('percent-open')).toInteger()
          BigDecimal currentTemp = getRoomTemp(vent)
          BigDecimal lastStartTemp = vent.currentValue('room-starting-temperature-c')
          def ratePropName = data.hvacMode == COOLING ? 'room-cooling-rate' : 'room-heating-rate'
          def currentRate = vent.currentValue(ratePropName)
          def newRate = calculateRoomChangeRate(lastStartTemp, currentTemp, totalCycleMinutes, percentOpen, currentRate)
          if (newRate <= 0) { break }
          def rate = rollingAverage(currentRate, newRate, percentOpen / 100, 4)
          sendEvent(vent, [name: ratePropName, value: rate])
        } catch (err) {
          log.error(err)
        }
      }
    }
  }  
}

def recordStartingTemperatures() {
  if (!atomicState.ventsByRoomId) { return }
  atomicState.ventsByRoomId.each { roomId, ventIds ->
    for (ventId in ventIds) {
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { break }
        BigDecimal currentTemp = getRoomTemp(vent)
        sendEvent(vent, [name: 'room-starting-temperature-c', value: currentTemp])
      } catch (err) {
        log.error(err)
      }
    }
  }
}

def initializeRoomStates(hvacMode) {
  if (!dabEnabled) { return }
  log("Initializing room states - hvac mode: ${hvacMode})", 3)
  if (!atomicState.ventsByRoomId) { return }
  // Get the target temperature from the thermostat
  BigDecimal setpoint = getThermostatSetpoint(hvacMode)
  if (!setpoint) { return }
  atomicStateUpdate('thermostat1State', 'startedCycle', now())
  def rateAndTempPerVentId = getAttribsPerVentId(atomicState.ventsByRoomId, hvacMode)

  // Get longest time to reach to target temp
  def maxRunningTime = atomicState.maxHvacRunningTime ?: MAX_MINUTES_TO_SETPOINT
  def longestTimeToGetToTarget = calculateLongestMinutesToTarget(
    rateAndTempPerVentId, hvacMode, setpoint, maxRunningTime,
    settings.thermostat1CloseInactiveRooms)
  if (longestTimeToGetToTarget < 0) {
    log("All vents already reached setpoint (setpoint: ${setpoint})", 3)
    longestTimeToGetToTarget = maxRunningTime
  }
  if (longestTimeToGetToTarget == 0) {
    log("Openning all vents (setpoint: ${setpoint})", 3)
    openAllVents(atomicState.ventsByRoomId, MAX_PERCENTAGE_OPEN)
    return
  }
  log("Initializing room states - setpoint: ${setpoint}, longestTimeToGetToTarget: ${roundBigDecimal(longestTimeToGetToTarget)}", 3)

  def calculatedPercentOpenPerVentId = calculateOpenPercentageForAllVents(
    rateAndTempPerVentId, hvacMode, setpoint, longestTimeToGetToTarget,
    settings.thermostat1CloseInactiveRooms)

  if (calculatedPercentOpenPerVentId.size() == 0) {
    log("No vents are being changed (setpoint: ${setpoint})", 3)
    return
  }

  // Ensure mimimum combined vent flow across vents
  calculatedPercentOpenPerVentId = adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, hvacMode,
    calculatedPercentOpenPerVentId, settings.thermostat1AdditionalStandardVents)

  // Apply open percentage across all vents
  calculatedPercentOpenPerVentId.each { ventId, percentOpen ->
    def vent = getChildDevice(ventId)
    if (vent) {
      patchVent(vent, roundToNearestFifth(percentOpen))
    }
  }
}

def adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, hvacMode,
  calculatedPercentOpenPerVentId, additionalStandardVents) {
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
  BigDecimal maxTemp = null
  BigDecimal minTemp = null
  rateAndTempPerVentId.each { ventId, stateVal ->
    maxTemp = maxTemp == null || maxTemp < stateVal.temp ? stateVal.temp : maxTemp
    minTemp = minTemp == null || minTemp > stateVal.temp ? stateVal.temp : minTemp
  }
  minTemp = minTemp - 0.1
  maxTemp = maxTemp + 0.1
  def combinedVentFlowPercentage = (100 * sumPercentages) / (totalDeviceCount * 100)
  if (combinedVentFlowPercentage >= MIN_COMBINED_VENT_FLOW_PERCENTAGE) {
    log("Combined vent flow percentage (${combinedVentFlowPercentage}) is greather than ${MIN_COMBINED_VENT_FLOW_PERCENTAGE}", 3)
    return calculatedPercentOpenPerVentId
  }
  log("Combined Vent Flow Percentage (${combinedVentFlowPercentage}) is lower than ${MIN_COMBINED_VENT_FLOW_PERCENTAGE}%", 3)
  def targetPercentSum = MIN_COMBINED_VENT_FLOW_PERCENTAGE * totalDeviceCount
  def diffPercentageSum = targetPercentSum - sumPercentages
  log("sumPercentages=${sumPercentages}, targetPercentSum=${targetPercentSum}, diffPercentageSum=${diffPercentageSum}", 2)
  def iterations = 0
  while (diffPercentageSum > 0 && iterations++ < MAX_ITERATIONS) {
    for (item in rateAndTempPerVentId) {
      def ventId = item.key
      def stateVal = item.value
      def percentOpenVal = calculatedPercentOpenPerVentId?."${ventId}" ?: 0
      if (percentOpenVal >= MAX_PERCENTAGE_OPEN) {
        percentOpenVal = MAX_PERCENTAGE_OPEN
      } else {
        def proportion = hvacMode == COOLING ?
          (stateVal.temp - minTemp) / (maxTemp - minTemp) :
          (maxTemp - stateVal.temp) / (maxTemp - minTemp)
        def increment = INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET * proportion
        percentOpenVal = percentOpenVal + increment
        calculatedPercentOpenPerVentId."${ventId}" = percentOpenVal
        log("Adjusting % open from ${roundBigDecimal(percentOpenVal - increment)}% to ${roundBigDecimal(percentOpenVal)}%", 2)
        diffPercentageSum = diffPercentageSum - increment
        if (diffPercentageSum <= 0) { break }
      }
    }
  }
  return calculatedPercentOpenPerVentId
}

def getAttribsPerVentId(ventIdsByRoomId, hvacMode) {
  def rateAndTempPerVentId = [:]
  ventIdsByRoomId.each { roomId, ventIds ->
    for (ventId in ventIds) {
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { break }
        def rate = hvacMode == COOLING ?
          vent.currentValue('room-cooling-rate') :
          vent.currentValue('room-heating-rate')
        rate = rate ?: 0
        def isRoomActive = vent.currentValue('room-active') == 'true'
        rateAndTempPerVentId."${ventId}" = [
          'rate':  rate,
          'temp': getRoomTemp(vent),
          'active': isRoomActive,
          'name': vent.currentValue('room-name')
        ]
      } catch (err) {
        log.error(err)
      }
    }
  }
  return rateAndTempPerVentId
}

def calculateOpenPercentageForAllVents(rateAndTempPerVentId,
   hvacMode, setpoint, longestTimeToGetToTarget, closeInactiveRooms = true) {
  def calculatedPercentOpenPerVentId = [:]
  rateAndTempPerVentId.each { ventId, stateVal ->
    try {
      def percentageOpen = MIN_PERCENTAGE_OPEN
      if (closeInactiveRooms == true && !stateVal.active) {
        log("Closing vent on inactive room: ${stateVal.name}", 3)
      } else if (stateVal.rate < MIN_TEMP_CHANGE_RATE_C) {
        log("Opening vents at max since change rate is lower than minumal: ${stateVal.name}", 3)
        percentageOpen = MAX_PERCENTAGE_OPEN
      } else {
        percentageOpen = calculateVentOpenPercentange(stateVal.name, stateVal.temp, setpoint, hvacMode,
          stateVal.rate, longestTimeToGetToTarget)
      }
      calculatedPercentOpenPerVentId."${ventId}" = percentageOpen
    } catch (err) {
      log.error(err)
    }
  }
  return calculatedPercentOpenPerVentId
}

def calculateVentOpenPercentange(room, startTemp, setpoint, hvacMode, maxRate, longestTimeToGetToTarget) {
  if (hasRoomReachedSetpoint(hvacMode, setpoint, startTemp)) {
    def msgTemp = hvacMode == COOLING ? 'cooler' : 'warmer'
    log("'${room}' is already ${msgTemp} (${startTemp}) than setpoint (${setpoint})", 3)
    return MIN_PERCENTAGE_OPEN
  }
  def percentageOpen = MAX_PERCENTAGE_OPEN
  if (maxRate > 0 && longestTimeToGetToTarget > 0) {
    def targetRate = Math.abs(setpoint - startTemp) / longestTimeToGetToTarget
    percentageOpen = BASE_CONST * Math.exp((targetRate / maxRate) * EXP_CONST)
    percentageOpen = roundBigDecimal(percentageOpen * 100)
    log("changing percentage open for ${room} to ${percentageOpen}% (maxRate=${roundBigDecimal(maxRate)})", 3)
    if (percentageOpen < MIN_PERCENTAGE_OPEN) {
      percentageOpen = MIN_PERCENTAGE_OPEN
    } else if (percentageOpen > MAX_PERCENTAGE_OPEN) {
      percentageOpen = MAX_PERCENTAGE_OPEN
    }
  }
  return percentageOpen
}

def calculateLongestMinutesToTarget(rateAndTempPerVentId, hvacMode, setpoint, maxRunningTime,
  closeInactiveRooms = true) {
  def longestTimeToGetToTarget = -1
  rateAndTempPerVentId.each { ventId, stateVal ->
    try {
      def minutesToTarget = -1
      def rate = stateVal.rate
      if (closeInactiveRooms == true && !stateVal.active) {
        log("'${stateVal.name}' is inactive", 3)
      } else if (hasRoomReachedSetpoint(hvacMode, setpoint, stateVal.temp)) {
        log("'${stateVal.name}' has already reached  setpoint", 3)
      } else if (rate > 0) {
        minutesToTarget = Math.abs(setpoint - stateVal.temp) / rate
      } else if (rate == 0) {
        minutesToTarget = 0
      }
      if (minutesToTarget > maxRunningTime) {
        log.warn("'${stateVal.name}' is estimated to take ${roundBigDecimal(minutesToTarget)} minutes " +
          "to reach target temp, which is longer than the average ${roundBigDecimal(maxRunningTime)} minutes")
        minutesToTarget = maxRunningTime
      }
      if (longestTimeToGetToTarget < minutesToTarget) {
        longestTimeToGetToTarget = minutesToTarget
      }
      log("atomicState.ventsByRoomId: name=${stateVal.name}, roomTemp=${stateVal.temp}", 3)
    } catch (err) {
      log.error(err)
    }
  }
  return longestTimeToGetToTarget
}

def calculateRoomChangeRate(lastStartTemp, currentTemp, totalMinutes, percentOpen, currentRate) {
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
  BigDecimal approxEquivMaxRate = (rate / Math.max(rate, currentRate)) / pOpen

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

