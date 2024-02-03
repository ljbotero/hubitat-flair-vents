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
 *  version: 0.0.1
 */

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

        if (state?.flairAccessToken != null) {
          section {
              input 'discoverDevices', 'button', title: 'Discover', submitOnChange: true
          }
          listDiscoveredDevices()

        } else {
          section {
            paragraph 'Device discovery button is hidden until authorization is completed.'
          }
        }
        if (state?.authError) {
          section {
            paragraph "${state?.authError}"
          }
        }
        section{
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
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
  logDebug 'Hubitat Flair App updating'
}

def installed() {
  logDebug 'Hubitat Flair App installed'
}

def uninstalled() {
  logDebug 'Hubitat Flair App uninstalling'
  removeChildren()
  unschedule()
  unsubscribe()
}

def initialize(evt) {
  logDebug(evt)
}

void removeChildren() {
  def children = getChildDevices()
  logDebug("Deleting all child devices: ${children}")
  children.each {
        if (it != null) {
      deleteChildDevice it.getDeviceNetworkId()
        }
  }
}

private logDebug(msg) {
  if (settings?.debugOutput) {
    log.debug(msg)
  }
}

// OAuth

def login() {
  logDebug('Getting access_token from Flair')
  def uri = 'https://api.flair.co/oauth2/token'
  def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}&scope=vents.view+vents.edit+structures.view+structures.edit&grant_type=client_credentials"
  def params = [uri: uri, body: body]
  try {
      httpPost(params) { response -> handleLoginResponse(response) }
      state.authError = ''
    } catch (groovyx.net.http.HttpResponseException e) {
      String err = "Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}"
      log.error(err)
      state.authError = err
      return err
  }
  return ''
}

def handleLoginResponse(resp) {
  //def respCode = resp.getStatus()
  def respJson = resp.getData()
  logDebug("Authorized scopes: ${respJson.scope}")
  state.flairAccessToken = respJson.access_token
}

// Get devices

def appButtonHandler(btn) {
  switch (btn) {
    case 'discoverDevices':
      discover()
      break
  }
}

private void discover() {
  logDebug('Discovery started')
  def uri = 'https://api.flair.co/api/vents'
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = 'application/json'
  def params = [ uri: uri, headers: headers, contentType: contentType ]
  asynchttpGet(handleDeviceList, params, [params: params])
}

def handleDeviceList(resp, data) {
  def respCode = resp.getStatus()
  if (resp.hasError()) {
    def respError = resp.getErrorData()
    log.warn("Device-list response code: ${respCode}, body: ${respError}")
    } else {
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
}

def makeRealDevice(device) {
  def deviceType = "Flair ${device.type}"
  try {
    addChildDevice(
            'bot.flair',
            deviceType.toString(),
            device.id,
            [
                name: device.label,
                label: device.label
            ]
        )
    } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
    log.warn("${e.message} - you need to install the appropriate driver: ${device.type}")
    } catch (IllegalArgumentException ignored) {
    //Intentionally ignored.  Expected if device id already exists in HE.
    getChildDevice(device.id)
  }
}

def getDeviceData(com.hubitat.app.DeviceWrapper device) {
  logDebug("Refresh device details for ${device}")
  def deviceId = device.getDeviceNetworkId()

  def uri = 'https://api.flair.co/api/vents/' + deviceId + '/current-reading'
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = 'application/json'
  def params = [ uri: uri, headers: headers, contentType: contentType ]
  asynchttpGet(handleDeviceGet, params, [device: device, params: params])

  uri = 'https://api.flair.co/api/vents/' + deviceId + '/room'
  params = [ uri: uri, headers: headers, contentType: contentType ]
  asynchttpGet(handleRoomGet, params, [device: device, params: params])
  
}

// Get device data

def handleRoomGet(resp, data) {
  def respCode = resp.getStatus()
  if (resp.hasError()) {
    def respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
    log.error("Device-get response code: ${respCode}, body: ${respError}")
  } else {
    fullDevice = getChildDevice(data.device.getDeviceNetworkId())
    processRoomTraits(fullDevice, resp.getJson())
  }
}

def handleDeviceGet(resp, data) {
  def respCode = resp.getStatus()
  if (resp.hasError()) {
    def respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
    log.error("Device-get response code: ${respCode}, body: ${respError}")
  } else {
    fullDevice = getChildDevice(data.device.getDeviceNetworkId())
    processVentTraits(fullDevice, resp.getJson())
  }
}

def traitExtract(device, details, propNameData, propNameDriver = propNameData, unit = null) {
  def propValue = details.data.attributes[propNameData]
  if (propValue != null) {
    if (unit) {
      sendEvent(device, [name: propNameDriver, value: propValue, unit: unit])
    } else {
      sendEvent(device, [name: propNameDriver, value: propValue])
    }
    //logDebug("${propName} = ${propValue}")
  }
}

def processVentTraits(device, details) {
  logDebug("Processing Vent data for ${device}: ${details}")

  if (!details.data) {
    return;
  }
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
  logDebug("Processing Room data for ${device}: ${details}")

  if (!details.data) {
    return;
  }
  sendEvent(device, [name: 'room-id', value: details.data.id])  
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
}

// Operations

def patchVent(com.hubitat.app.DeviceWrapper device, percentOpen) {
  logDebug("Setting percent open for ${device} to ${percentOpen}%")
  def deviceId = device.getDeviceNetworkId()

  def uri = 'https://api.flair.co/api/vents/' + deviceId
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = 'application/json'
  def body = [
    data: [
      type: "vents", 
      attributes: [
        "percent-open": percentOpen
      ]
    ]
  ]
  def params = [
    uri: uri, 
    headers: headers, 
    contentType: contentType, 
    requestContentType: contentType,
    body: groovy.json.JsonOutput.toJson(body)
  ]
  logDebug "sendAsynchttpPatch:${uri}, body:${params}"
  asynchttpPatch(handleVentPatch, params, [device: device])
}

def handleVentPatch(resp, data) {
  def respCode = resp.getStatus()
  if (resp.hasError()) {
    def respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
    log.error("Device-get response code: ${respCode}, body: ${respError}")
  } else {
    fullDevice = getChildDevice(data.device.getDeviceNetworkId())
    traitExtract(fullDevice, resp.getJson(), 'percent-open', '%')
  }
}


def patchRoom(com.hubitat.app.DeviceWrapper device, active) {
  def roomId = device.currentValue("room-id")
  logDebug("Setting room attributes for ${roomId} to active:${active}%")
  if (!roomId || active == null) {
    return
  }
  
  def uri = 'https://api.flair.co/api/rooms/' + roomId
  def headers = [ Authorization: 'Bearer ' + state.flairAccessToken ]
  def contentType = 'application/json'
  def body = [
    data: [
      type: "rooms", 
      attributes: [
        "active": active == 'true' ? true: false
      ]
    ]
  ]
  
  def params = [
    uri: uri, 
    headers: headers, 
    contentType: contentType, 
    requestContentType: contentType,
    body: groovy.json.JsonOutput.toJson(body)
  ]
  logDebug "sendAsynchttpPatch:${uri}, body:${params}"
  asynchttpPatch(handleRoomPatch, params, [device: device])
}

def handleRoomPatch(resp, data) {
  def respCode = resp.getStatus()
  if (resp.hasError()) {
    def respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
    log.error("Device-get response code: ${respCode}, body: ${respError}")
  } else {
    fullDevice = getChildDevice(data.device.getDeviceNetworkId())
    traitExtract(fullDevice, resp.getJson(), 'active', 'room-active')
  }
}
