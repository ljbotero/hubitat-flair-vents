import groovy.transform.Field

@Field static String OCCUPIED = 'occupied'

definition(
    name: 'Ecobee Smart Participation',
    namespace: 'bot.ecobee.smart',
    author: 'Jaime Botero',
    description: 'Chooses the most critical sensors in sensor participation based on temperatures',
    category: 'Discovery',
    oauth: false,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    singleInstance: false
)

preferences {
  page(name: 'mainPage')
}

def mainPage() {
  dynamicPage(name: 'mainPage', title: 'Setup', install: true, uninstall: true) {
    section {
      input 'enabled', title: 'Enabled', submitOnChange: true, defaultValue: true, 'bool'
      input 'thermostat', title: 'Choose Thermostat', submitOnChange: true,
        multiple: false, required: true, 'capability.thermostat'
      input 'sensors', title: 'Choose Sensors', multiple: true, required: true,
        'capability.temperatureMeasurement'
      input name: 'programs', type: 'enum', title: 'Participating Programs', submitOnChange: true,
        required: true, multiple: true, options: getThermostatPrograms()
      input name: 'range', type: 'enum', title: 'Range of temperatures to include as participating sensors', defaultValue: 2,
        options: [4:'Top/Bottom Quarter', 3:'Top/Bottom Third', 2:'Median'], submitOnChange: true
      input 'occupancyModes', title: 'Modes Where Occupancy is Used',  multiple: true, 'mode'
      input name: 'debugLevel', type: 'enum', title: 'Choose debug level', defaultValue: 0,
        options: [0:'None', 1:'Level 1 (All)', 2:'Level 2', 3:'Level 3'], submitOnChange: true
    }
  }
}

def updated() {
  log.debug('Updated')
  initialize()
}

def installed() {
  log.debug('Installed')
  initialize()
}

def uninstalled() {
  log.debug('Uninstalling')
  unschedule('recalculateSensorParticipation')
  unsubscribe()
}

def initialize() {
  unsubscribe(settings.thermostat, 'thermostatOperatingState')
  subscribe(settings.thermostat, 'thermostatOperatingState', thermostatChangeStateHandler)
  thermostatChangeStateHandler([value: 'Initializing'])
}

// Main Logic

def thermostatChangeStateHandler(evt) {
  log("thermostat changed state to:${evt.value}", 3)
  unschedule('recalculateSensorParticipation')
  runEvery1Hour('recalculateSensorParticipation')
  recalculateSensorParticipation()
}

def recalculateSensorParticipation() {
  BigDecimal coolingSetpoint = thermostat.currentValue('coolingSetpoint')
  BigDecimal heatingSetpoint = thermostat.currentValue('heatingSetpoint')
  def sensorsList = [:]
  sensors.each {  sensor ->
    sensorsList."${sensor.id}" = [
        'occupancy': sensor.currentValue('occupancy'),
        'temperature': sensor.currentValue('temperature')
    ]
  }
  def useOccupancy = occupancyModes.any { it == location.mode }
  def sensorPrograms = recalculateSensorParticipationInit(
      sensorsList, heatingSetpoint, coolingSetpoint, useOccupancy)

  sensors.each {  sensor ->
    try {
      def temp = roundBigDecimal(sensor.currentValue('temperature'))
      if (sensorPrograms[sensor.id]) {
        log(" ${temp}F: addSensorToPrograms('${sensor.getLabel()}', ${programs})")
        sensor.addSensorToPrograms(programs)
      } else {
        log(" ${temp}F: deleteSensorFromPrograms('${sensor.getLabel()}', ${programs})")
        sensor.deleteSensorFromPrograms(programs)
      }
    } catch (err) {
      log.error(err)
    }
  }
}

def recalculateSensorParticipationInit(sensorsList, heatingSetpoint, coolingSetpoint, useOccupancy) {
  BigDecimal sumTemps = 0
  BigDecimal highestTemp = 0
  BigDecimal lowestTemp = 0
  def activeSensorCount = 0
  sensorsList.each {  id, props ->
    def isOccupied = useOccupancy ? props.occupancy == OCCUPIED : true
    if (!isOccupied) { return }
    def temp = props.temperature
    sumTemps += temp
    activeSensorCount++
    if (temp > highestTemp) {
      highestTemp = temp
    }
    if (lowestTemp == 0 || temp < lowestTemp) {
      lowestTemp = temp
    }
  }
  if (activeSensorCount == 0) {
    log.error('Active sensor count is zero')
    return [:]
  }
  def avgTemp = sumTemps / activeSensorCount
  def medSetPoint = heatingSetpoint + ((coolingSetpoint - heatingSetpoint) / 2)
  log("${useOccupancy ? 'Using occupancy' : 'Not using occupancy'}, " +
    "Avg temps: ${roundBigDecimal(avgTemp)}, " +
    "median set point: ${roundBigDecimal(medSetPoint)}, " +
    "lowest Temp: ${roundBigDecimal(lowestTemp)}, " +
    "highest Temp: ${roundBigDecimal(highestTemp)}, " +
    "${avgTemp > medSetPoint ? 'Cooling' : 'Heating'}", 3)

  def sensorPrograms = [:]
  def rangeLevel = (settings?.range).toInteger()
  if (avgTemp > medSetPoint) {
    // Cooling
    def tempsHottestQuarter = highestTemp - ((highestTemp - lowestTemp) / rangeLevel)
    sensorsList.each {  id, props ->
      def isOccupied = useOccupancy ? props.occupancy == OCCUPIED : true
      if (!isOccupied) { return }
      def temp = props.temperature
      if (temp >= tempsHottestQuarter) {
        sensorPrograms."${id}" = true
      }
    }
  } else {
    // Heating
    def tempsColdestQuarter = lowestTemp + ((highestTemp - lowestTemp) / rangeLevel)
    sensorsList.each {  id, props ->
      def isOccupied = useOccupancy ? props.occupancy == OCCUPIED : true
      if (!isOccupied) { return }
      def temp = props.temperature
      if (temp <= tempsColdestQuarter) {
        sensorPrograms."${id}" = true
      }
    }
  }
  return sensorPrograms
}

// Helpers

private getThermostatPrograms() {
  if (thermostat) {
    return new groovy.json.JsonSlurper().parseText(thermostat.currentValue('programsList'))
  }
  return ['none']
}

private log(msg, level = 3) {
  def settingsLevel = (settings?.debugLevel).toInteger()
  if (settingsLevel == 0) {
    return
  }
  if (settingsLevel <= level) {
    log.debug(msg)
  }
}

def roundBigDecimal(BigDecimal number, decimalPoints = 3) {
  number.setScale(decimalPoints, BigDecimal.ROUND_HALF_UP)
}
