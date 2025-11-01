package gt.app

import gt.app.test.A
import spock.lang.Specification

class DISpec extends Specification {

    def "length of Spock's and his friends' names"() {
        expect:
        DI.findClassesAnnotatedWith("gt.app.test", A.class).size() == 2


    }
}
