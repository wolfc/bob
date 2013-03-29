package org.jboss.bob.example;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public class GreeterTestCase {
    @Test
    public void testSayHi() {
        final GreeterBean bean = new GreeterBean();
        final String result = bean.sayHi("test");
        assertEquals("Hi test", result);
    }
}
