import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DivisorTester {
    
    Divisor divisor;
    
    @BeforeClass
    public static void beforeClass() {
        divisor = new Divisor();
    }
    
	@Test
    public void test1() {
        assertEquals("Test 1, n = 10000", 25, divisor.numberOfDivisors(10000));        
    } 	
    
    @Test
    public void test2() {
        assertEquals("Test 2, n = 100", 9, divisor.numberOfDivisors(100));        
    }
    
    @Test
    public void test3() {
        assertEquals("Test 3, n = 1283", 16, divisor.numberOfDivisors(1283));        
    }
}
