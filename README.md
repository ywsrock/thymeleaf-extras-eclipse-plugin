
Thymeleaf - Eclipse Plugin module
=================================

A plugin for the Eclipse IDE to add content assist features for the Thymeleaf
standard dialect processors and expression utility objects, using the Eclipse
Web Tools Platform HTML source editor.

 - Current version: 2.1.2
 - Released: 4 March 2016

The 2.1.x versions of the Eclipse plugin are for Thymeleaf 2.1.  Check out the
[2.0-master branch](https://github.com/thymeleaf/thymeleaf-extras-eclipse-plugin/tree/2.0-master)
for a version that supports Thymeleaf 2.0.


Minimum Requirements
--------------------

 - Java 6
 - Thymeleaf 2.1.0
 - Eclipse Indigo SR2 (3.7.2) w/ Web Tools Platform 3.3.2 (ie: the Java EE
   Developer bundle)


Installation
------------

In Eclipse, go to Help >> Install New Software... then either use the update
site URL, or download a ZIP archive of the plugin from SourceForge:

 - Update site URL: [http://www.thymeleaf.org/eclipse-plugin-update-site/](http://www.thymeleaf.org/eclipse-plugin-update-site/)
 - ZIP file downloads: [https://bintray.com/thymeleaf/downloads/thymeleaf-extras-eclipse-plugin/](https://bintray.com/thymeleaf/downloads/thymeleaf-extras-eclipse-plugin/)


Features
--------

### Content Assist

Content assist features are only available for dialects which have supplied
special dialect metadata files in their JARs.  thymeleaf-core, thymeleaf-spring3,
thymeleaf-extras-springsecurity3, and thymeleaf-extras-tiles2 projects already
have such files.  Other dialects, however, are up to the discretion of their
developer(s).  If you're developing a Thymeleaf dialect and would like to take
advantage of content assist for your own dialect, read the section on
[adding content assist for your dialect](#adding-content-assist-for-your-dialect).

Once those help files are available, you can make content assist available in
your HTML files through 1 of 2 ways:

#### 1. Declaring the dialect namespace and prefix in your HTML files

This is the easiest method and you may have already done this to keep the XML
validator happy:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
```

#### 2. Applying the Thymeleaf project nature to your project

This method will make content assist available to _all_ of the HTML files in
your project, and is ideal for when you've organized your code to have plenty of
reusable HTML fragments without a common root element on which to put the XML
namespace.

To add the Thymeleaf nature to your project: right-click a project >> Thymeleaf >>
Add Thymeleaf Nature.

Using either method, you should now start getting content assist for any dialect
whose namespace is explicitly declared in your HTML files (method 1), or for
every dialect in your project's classpath (method 2).  This applies to
suggestions as you type, autocompletion of what you've entered so far if it
matches only one result (both of these can be invoked manually using CTRL+SPACE),
and help text when hovering the cursor over a Thymeleaf processor.


Adding content assist for your dialect
--------------------------------------

The content assist features are driven by metadata about a dialect, currently
done using XML files, conforming to a schema that lives at
[http://www.thymeleaf.org/xsd/thymeleaf-extras-dialect-2.1.xsd](http://www.thymeleaf.org/xsd/thymeleaf-extras-dialect-2.1.xsd).

When content assist is invoked, this plugin will look for XML files in the
classpath of the current project whose XML namespace is `http://www.thymeleaf.org/extras/dialect`.
If such a file is found, it is loaded and the information in it is used to form
the content assist data that the Eclipse plugin uses.

Dialect developers can take advantage of this by including XML help files as
part of their dialect JARs.  All you need to do is create an XML file that
conforms to the schema above, then bundle that XML file with your JAR.

Some notes on where you put that file in the JAR:

 - it cannot go in the default package
 - the directory it goes in must be a valid Java package name

These are just short-comings of the current dialect scanning method, which
itself is built upon Eclipse's own lookup mechanisms.
