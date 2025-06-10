package bot.flair

// Simple Framework Verification Test
import spock.lang.Specification

class SimpleFrameworkTest extends Specification {
    
    def "framework should work"() {
        expect:
        1 + 1 == 2
    }
    
    def "basic string test"() {
        given:
        def str = "hello"
        
        expect:
        str.length() == 5
        str.toUpperCase() == "HELLO"
    }
}
