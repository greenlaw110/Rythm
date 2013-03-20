package com.greenlaw110.rythm.issue;

import models.Foo;
import models.License;

import org.junit.Test;

import com.greenlaw110.rythm.TestBase;
import com.greenlaw110.rythm.conf.RythmConfigurationKey;
import com.greenlaw110.rythm.extension.ICodeType;

/**
 * Test Github Issues
 */
public class GHIssueTest extends TestBase {
    @Test
    public void test116() {
        t = "PlayRythm Demo - @get(\"title\")";
        s = r(t);
        eq("PlayRythm Demo - ");
    }
    
    @Test
    public void test117() {
        System.getProperties().put("default.template_lang.impl", ICodeType.DefImpl.CSV);
        t = "@for(\"FirstName,LastName,Email\"){@__sep}";
        s = r(t);
        eq("FirstName,LastName,Email");
    }
    
    @Test
    public void test120() {
        t = "@def String x(boolean x) {if (x) {return \"x\";} else {return \"y\";}}@x(true)";
        s = r(t);
        eq("x");
    }
    
    @Test
    public void test122() {
        System.getProperties().put(RythmConfigurationKey.DEFAULT_CODE_TYPE_IMPL.getKey(), ICodeType.DefImpl.HTML);
        t = "@args String src;<script src='@src'></script><script src='@src'></script>";
        s = r(t, "/js/abc");
        eq("<script src='/js/abc'></script><script src='/js/abc'></script>");
        
        t = "@args models.Foo foo;<script src='@foo.bar()._x()'></script>";
        s = r(t, new Foo());
        eq("<script src=''></script>");
    }
    
    @Test
    public void test123() {
        t = "@args models.Foo foo;@foo.bar()._x()";
        s = r(t, new Foo());
        eq("");
    }
    
    @Test
    public void test124() {
        t = "@args models.License license;@license.getLicense_type()";
        License license = new License();
        license.setLicense_type("1");
		s = r(t, license);
        eq("1");
    }
    
    @Test
    public void test125() {
        t = "@args models.License license;@license.license_type";
        License license = new License();
        license.setLicense_type("1");
		s = r(t, license);
        eq("1");
    }
    
    public static void main(String[] args) {
        run(GHIssueTest.class);
    }
}
