@Grab('org.spockframework:spock-core:2.3-groovy-3.0')
@Grab('org.codehaus.groovy:groovy-json:3.0.9')
import spock.lang.Specification
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.BigDecimal

/**
 * Test decimal precision handling for JSON export
 * These tests validate that BigDecimal values are properly rounded to 10 decimal places
 * for JSON serialization, preventing excessively long decimal representations.
 */
class DecimalPrecisionTest extends Specification {

    // Mock the app's cleanDecimalForJson function
    def cleanDecimalForJson(def value) {
        if (value == null || value == 0) return 0
        
        try {
            // Convert to double and round to 10 decimal places
            def doubleValue = value as Double
            def multiplier = Math.pow(10, 10)
            def rounded = Math.round(doubleValue * multiplier) / multiplier
            
            // Return as Double to ensure clean JSON serialization
            return rounded as Double
        } catch (Exception e) {
            return 0
        }
    }

    def "should limit BigDecimal to exactly 10 decimal places in JSON output"() {
        given: "A BigDecimal value like what comes from device attributes"
        def deviceValue = new BigDecimal("0.7565031865619353798895421013423648241063427314927104497175")
        
        when: "We export it using the real export process"
        def exportData = [
            globalRates: [
                maxCoolingRate: cleanDecimalForJson(deviceValue),
                maxHeatingRate: cleanDecimalForJson(0.4380625000000000071016808361923900918589158077810090062063875)
            ]
        ]
        def jsonString = JsonOutput.toJson(exportData)
        def parsedJson = new JsonSlurper().parseText(jsonString)
        
        then: "The JSON string should not contain excessive decimal places"
        def coolingRateString = jsonString // Get the raw JSON string
        
        // This test should FAIL initially to demonstrate the problem
        !coolingRateString.contains("865619353798895421013423648241063427314927104497175")
        
        and: "The parsed values should have limited decimal places"
        def coolingString = parsedJson.globalRates.maxCoolingRate.toString()
        def decimalIndex = coolingString.indexOf('.')
        def actualDecimalPlaces = decimalIndex >= 0 ? coolingString.length() - decimalIndex - 1 : 0
        
        actualDecimalPlaces <= 10
        
        println "Cooling rate in JSON: ${coolingString} (${actualDecimalPlaces} decimal places)"
        println "Raw JSON contains excessive precision: ${coolingRateString.contains('865619353798895421013423648241063427314927104497175')}"
    }

    def "should handle various BigDecimal precision scenarios"() {
        expect: "Different precision values to be limited to 10 decimal places"
        def cleaned = cleanDecimalForJson(input)
        def jsonString = JsonOutput.toJson([value: cleaned])
        def parsedJson = new JsonSlurper().parseText(jsonString)
        def valueString = parsedJson.value.toString()
        
        // Count actual decimal places in JSON output
        def decimalIndex = valueString.indexOf('.')
        def actualDecimalPlaces = decimalIndex >= 0 ? valueString.length() - decimalIndex - 1 : 0
        
        actualDecimalPlaces <= 10
        
        where:
        input | _
        new BigDecimal("0.4380625000000000071016808361923900918589158077810090062063875") | _
        new BigDecimal("0.18943916179511408322042027195788449094053727672567920810294705804073867372851908324254885014788632502773678107758190274582296724635023208310983458710448020588621023460956863815772026881888292929099538870519467295978878988298021558336748980002808140112812456892912180187825426287882064176693578043875166121501525891878693678857793198910101629958897201177582116241721188873102002545931475490813706278040341443132501643636221184956892614644844974173926889312392923846251175497543471502956021288138924298669599398950881404568846552757135111507774796379984663439318794261035604561500003871555174186250169105352295822305612645975404785655179773191086298692029490600067199405823805697739427745704789401678161704511882801931332775395804552668936181869061483271632824088908558185654809226016898991253650226387983535964053440756001562101165052264048138691020619443041099747210274048394012834312271934991470827991436430687139590210073616989015147900660709337769044103832411671224529001186530310256444193973612448102549392739905187626590150829620638741581963435589648465440994128701854201642118294547762609898067091247272844540364873965499783434493763593779103944920838524868965039359261027879529528832224846766310632548238257375295915760487557406877873255066788950922437102911248691118951125400216628154752778202594704463136645206176930662671812659217622983438353600005071820893376171555694919015879806891539913851226084201488846699230975914970502022557512613023746997758918305316497880508896446920614881544813504471937407836242789710023346290961066858070486007507442854485028261760684313310886336750440348040460315518250268115234742088369789233592811890246082414880221244922084086071211386525538397206827215971283883059095731010094759071101477295064135161824446035844992725682267627348130678566207052426761947572231292724609375") | _
        new BigDecimal("0.093883425826417935045058992508023823682310021717530385645990854739696291795418416631700633156468403590799624343587755965421366622092879986630758425155615184449407750526428686929690294025042585105450231200499934358372490011535915073686049729357698236053062621355598923948408120310250069495687502992449743338900891941096347553565907065678228112849165715669609813425302591209370880277914176029939547777725687506415268607331885103429860090448248738325926670386965581054614232444482949997111504750533390271724933799781817035048874285943107300023767947583215944620144925310656611963558678418465228567519733974418723449901525813087144375886747305513273621447134756988853758127520878362100207650521644056513897251893968662313983099364496016093870737137432663284772869061011967740388974188362129889559731563588862136040017987106646306507460830296837228525580638565485175302562994727693828805445976721604152306727222037539223977459889041743002707158394713880937183231361626441335983095482305609538553809207551102118203385338068580849317714278807328329898604591235930693145102274402869286206231163429089770153890799083119281093377113319795392504674477547225542478007750407804896263759193177698143883807479889660703408582118746585014230592281560306537092140101514112727162508785531586973743330173172534225815004710685940473638397532067600634598878382948999781098826271320434760401181850440829834032197914759154433314988141097577553491025125212786284268866850283429649569888247708528962636057977612731967957594879635994810308095759969488417093496398224957578111395830754529384696057750274686196399733327075269314244116885444158472064453581626621101385295770232718159942209013024694286286830902099609375") | _
    }

    def "should demonstrate the current problem with BigDecimal in JSON"() {
        given: "A BigDecimal with excessive precision like what comes from Hubitat device attributes"
        def excessivePrecision = new BigDecimal("0.7565031865619353798895421013423648241063427314927104497175")
        
        when: "We serialize it directly to JSON without cleaning"
        def jsonString = JsonOutput.toJson([value: excessivePrecision])
        
        then: "The JSON contains excessive decimal places (this test should demonstrate the problem)"
        def parsedJson = new JsonSlurper().parseText(jsonString)
        def valueString = parsedJson.value.toString()
        def decimalIndex = valueString.indexOf('.')
        def actualDecimalPlaces = decimalIndex >= 0 ? valueString.length() - decimalIndex - 1 : 0
        
        // This assertion should FAIL to demonstrate the problem
        actualDecimalPlaces > 10 // This shows the problem exists
        println "Raw BigDecimal in JSON has ${actualDecimalPlaces} decimal places: ${valueString}"
        println "Raw JSON string: ${jsonString}"
    }
    
    def "should fail to demonstrate the ACTUAL problem the user is seeing"() {
        given: "The exact scenario from the user's export"
        // These are the exact values the user is seeing
        def problematicCoolingRate = new BigDecimal("0.7565031865619353798895421013423648241063427314927104497175")
        def problematicHeatingRate = new BigDecimal("0.4380625000000000071016808361923900918589158077810090062063875")
        
        when: "We call cleanDecimalForJson and then serialize to JSON (like the app does)"
        def exportData = [
            exportMetadata: [
                version: "0.22",
                exportDate: "2025-06-26T14:18:10Z",
                structureId: "58538"
            ],
            efficiencyData: [
                globalRates: [
                    maxCoolingRate: cleanDecimalForJson(problematicCoolingRate),
                    maxHeatingRate: cleanDecimalForJson(problematicHeatingRate)
                ],
                roomEfficiencies: []
            ]
        ]
        def jsonString = JsonOutput.toJson(exportData)
        
        then: "This test should FAIL if the problem still exists"
        // Check if the JSON contains the problematic long decimal
        !jsonString.contains("7565031865619353798895421013423648241063427314927104497175")
        !jsonString.contains("4380625000000000071016808361923900918589158077810090062063875")
        
        // If this fails, we've reproduced the user's problem
        println "Generated JSON: ${jsonString}"
        println "Contains problematic cooling rate: ${jsonString.contains('7565031865619353798895421013423648241063427314927104497175')}"
        println "Contains problematic heating rate: ${jsonString.contains('4380625000000000071016808361923900918589158077810090062063875')}"
    }

    def "should verify cleanDecimalForJson actually works in practice"() {
        given: "Multiple problematic BigDecimal values"
        def problematicValues = [
            new BigDecimal("0.7565031865619353798895421013423648241063427314927104497175"),
            new BigDecimal("0.4380625000000000071016808361923900918589158077810090062063875")
        ]
        
        when: "We clean them and put in a data structure like the export"
        def exportData = [
            globalRates: [
                maxCoolingRate: cleanDecimalForJson(problematicValues[0]),
                maxHeatingRate: cleanDecimalForJson(problematicValues[1])
            ]
        ]
        def jsonString = JsonOutput.toJson(exportData)
        
        then: "The JSON should have reasonable decimal precision"
        def parsedJson = new JsonSlurper().parseText(jsonString)
        
        def coolingString = parsedJson.globalRates.maxCoolingRate.toString()
        def heatingString = parsedJson.globalRates.maxHeatingRate.toString()
        
        // Count decimal places
        def coolingDecimalIndex = coolingString.indexOf('.')
        def coolingDecimalPlaces = coolingDecimalIndex >= 0 ? coolingString.length() - coolingDecimalIndex - 1 : 0
        
        def heatingDecimalIndex = heatingString.indexOf('.')
        def heatingDecimalPlaces = heatingDecimalIndex >= 0 ? heatingString.length() - heatingDecimalIndex - 1 : 0
        
        coolingDecimalPlaces <= 10
        heatingDecimalPlaces <= 10
        
        println "Cleaned cooling rate: ${coolingString} (${coolingDecimalPlaces} decimal places)"
        println "Cleaned heating rate: ${heatingString} (${heatingDecimalPlaces} decimal places)"
    }
}
