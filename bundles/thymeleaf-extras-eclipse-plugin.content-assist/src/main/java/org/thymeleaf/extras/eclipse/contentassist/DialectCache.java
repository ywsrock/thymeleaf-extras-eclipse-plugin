/*
 * Copyright 2013, The Thymeleaf Project (http://www.thymeleaf.org/)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.thymeleaf.extras.eclipse.contentassist;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavadocContentAccess;
import org.thymeleaf.extras.eclipse.dialect.BundledDialectLocator;
import org.thymeleaf.extras.eclipse.dialect.ProjectDependencyDialectLocator;
import org.thymeleaf.extras.eclipse.dialect.SingleFileDialectLocator;
import org.thymeleaf.extras.eclipse.dialect.XmlDialectLoader;
import org.thymeleaf.extras.eclipse.dialect.xml.AttributeProcessor;
import org.thymeleaf.extras.eclipse.dialect.xml.Dialect;
import org.thymeleaf.extras.eclipse.dialect.xml.DialectItem;
import org.thymeleaf.extras.eclipse.dialect.xml.Documentation;
import org.thymeleaf.extras.eclipse.dialect.xml.ElementProcessor;
import org.thymeleaf.extras.eclipse.dialect.xml.ExpressionObject;
import org.thymeleaf.extras.eclipse.dialect.xml.ExpressionObjectMethod;
import org.thymeleaf.extras.eclipse.dialect.xml.Processor;
import static org.eclipse.core.resources.IResourceChangeEvent.*;
import static org.thymeleaf.extras.eclipse.contentassist.ContentAssistPlugin.*;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.xml.namespace.QName;

/**
 * A basic in-memory store of all known Thymeleaf dialects and their processors
 * and expression object methods.
 * 
 * @author Emanuel Rabina
 */
public class DialectCache {

	private static final XmlDialectLoader xmldialectloader = new XmlDialectLoader();

	// List of bundled dialects
	private static final ArrayList<Dialect> bundleddialects = new ArrayList<Dialect>();

	// Mapping of projects that contain certain dialects
	private static final HashMap<IJavaProject,List<Dialect>> projectdialects =
			new HashMap<IJavaProject,List<Dialect>>();

	// Collection of processors in alphabetical order
	private static final TreeSet<Processor> processors = new TreeSet<Processor>(new Comparator<Processor>() {
		@Override
		public int compare(Processor p1, Processor p2) {
			Dialect d1 = p1.getDialect();
			Dialect d2 = p2.getDialect();
			return d1 == d2 ?
					p1.getName().compareTo(p2.getName()) :
					p1.getFullName().compareTo(p2.getFullName());
		}
	});

	// Collection of expression object methods in alphabetical order
	private static final TreeSet<ExpressionObjectMethod> expressionobjectmethods =
			new TreeSet<ExpressionObjectMethod>(new Comparator<ExpressionObjectMethod>() {
			@Override
			public int compare(ExpressionObjectMethod m1, ExpressionObjectMethod m2) {
				return m1.getName().compareTo(m2.getName());
			}
	});

	// Resource listener and cache updater
	private static DialectFileChangeListener dialectcacheupdater;

	/**
	 * Shutdown method of the cache, cleans up any processes that need
	 * cleaning-up.
	 */
	public static void close() {

		dialectcacheupdater.close();
	}

	/**
	 * Checks if the dialect is in the list of given namespaces.
	 * 
	 * @param dialect
	 * @param namespaces
	 * @return <tt>true</tt> if the dialect prefix (and namespace if the dialect
	 * 		   is namespace strict) are listed in the <tt>namespaces</tt>
	 * 		   collection.
	 */
	private static boolean dialectInNamespace(Dialect dialect, List<QName> namespaces) {

		for (QName namespace: namespaces) {
			if (dialect.getPrefix().equals(namespace.getPrefix())) {
				if (!dialect.isNamespaceStrict()) {
					return true;
				}
				else if (dialect.getNamespaceUri().equals(namespace.getNamespaceURI())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks if the dialect is included in the given project.
	 * 
	 * @param dialect
	 * @param project
	 * @return <tt>true</tt> if the project includes the dialect.
	 */
	private static boolean dialectInProject(Dialect dialect, IJavaProject project) {

		return projectdialects.get(project).contains(dialect);
	}

	/**
	 * Checks if the expression object method matches the given name.
	 * 
	 * @param method
	 * @param name
	 * @return <tt>true</tt> if the name matches the expression object name.
	 */
	private static boolean expressionObjectMethodMatchesName(ExpressionObjectMethod method, String name) {

		if (name == null || name.isEmpty()) {
			return false;
		}
		return name.equals(method.getFullName());
	}

	/**
	 * Checks if the expression object method name matches the given
	 * start-of-string pattern.
	 * 
	 * @param method
	 * @param pattern
	 * @return <tt>true</tt> if the pattern matches against the expression
	 * 		   object name.
	 */
	private static boolean expressionObjectMethodMatchesPattern(ExpressionObjectMethod method, String pattern) {

		return pattern != null && method.getFullName().startsWith(pattern);
	}

	/**
	 * Creates a documentation element from the Javadocs of a processor class.
	 * 
	 * @param processor
	 * @param project
	 * @return Documentation element with the processor's Javadoc content, or
	 * 		   <tt>null</tt> if the processor had no Javadocs on it.
	 */
	private static Documentation generateDocumentation(Processor processor, IJavaProject project) {

		String processorclassname = processor.getClazz();

		try {
			IType type = project.findType(processorclassname, new NullProgressMonitor());
			if (type != null) {
				Reader reader = JavadocContentAccess.getHTMLContentReader(type, false, false);
				if (reader != null) {
					try {
						StringBuilder javadoc = new StringBuilder();
						int nextchar = reader.read();
						while (nextchar != -1) {
							javadoc.append((char)nextchar);
							nextchar = reader.read();
						}
						Documentation documentation = new Documentation();
						documentation.setValue(javadoc.toString());
						return documentation;
					}
					finally {
						reader.close();
					}
				}
			}
		}
		catch (JavaModelException ex) {
			logError("Unable to access " + processorclassname + " in the project", ex);
		}
		catch (IOException ex) {
			logError("Unable to read javadocs from " + processorclassname, ex);
		}

		return null;
	}

	/**
	 * Creates expression object method suggestions from an expression object
	 * reference.
	 * 
	 * @param dialect		   Parent dialect.
	 * @param expressionobject The exression object reference.
	 * @return Set of expression object method suggestions based on the visible
	 * 		   methods of the expression object.
	 */
	private static HashSet<ExpressionObjectMethod> generateExpressionObjectMethods(Dialect dialect,
		ExpressionObject expressionobject) {

		HashSet<ExpressionObjectMethod> generatedmethods = new HashSet<ExpressionObjectMethod>();

		String classname = expressionobject.getClazz();
		IJavaProject project = findCurrentJavaProject();
		try {
			IType type = project.findType(classname);
			if (type != null) {
				for (IMethod method: type.getMethods()) {
					if (!method.isConstructor()) {

						ExpressionObjectMethod expressionobjectmethod = new ExpressionObjectMethod();
						expressionobjectmethod.setDialect(dialect);

						// For Java bean methods, convert the suggestion to a property
						String methodname = method.getElementName();
						int propertypoint =
								methodname.startsWith("get") || methodname.startsWith("set") ? 3 :
								methodname.startsWith("is") ? 2 :
								-1;

						if (propertypoint != -1 && methodname.length() > propertypoint &&
							Character.isUpperCase(methodname.charAt(propertypoint))) {

							StringBuilder propertyname = new StringBuilder(methodname.substring(propertypoint));
							propertyname.insert(0, Character.toLowerCase(propertyname.charAt(0)));
							propertyname.deleteCharAt(1);
							expressionobjectmethod.setName(expressionobject.getName() + "." + propertyname);
							expressionobjectmethod.setJavaBeanProperty(true);
						}
						else {
							expressionobjectmethod.setName(expressionobject.getName() + "." + methodname);
						}

						expressionobjectmethods.add(expressionobjectmethod);
					}
				}
			}
		}
		catch (JavaModelException ex) {
			logError("Unable to locate expression object reference: " + classname, ex);
		}

		return generatedmethods;
	}

	/**
	 * Retrieve all attribute processors for the given project, whose names
	 * match the starting pattern.
	 * 
	 * @param project	 The current project.
	 * @param namespaces List of namespaces available at the current point in
	 * 					 the document.
	 * @param pattern	 Start-of-string pattern to match.
	 * @return List of all matching attribute processors.
	 */
	public static List<AttributeProcessor> getAttributeProcessors(IJavaProject project,
		List<QName> namespaces, String pattern) {

		return getProcessors(project, namespaces, pattern, AttributeProcessor.class);
	}

	/**
	 * Retrieve all element processors for the given project, whose names match
	 * the starting pattern.
	 * 
	 * @param project	 The current project.
	 * @param namespaces List of namespaces available at the current point in
	 * 					 the document.
	 * @param pattern	 Start-of-string pattern to match.
	 * @return List of all matching element processors
	 */
	public static List<ElementProcessor> getElementProcessors(IJavaProject project,
		List<QName> namespaces, String pattern) {

		return getProcessors(project, namespaces, pattern, ElementProcessor.class);
	}

	/**
	 * Retrieve the expression object method with the full matching name.
	 * 
	 * @param project	 The current project.
	 * @param namespaces List of namespaces available at the current point in
	 * 					 the document.
	 * @param methodname Full name of the expression object method.
	 * @return Expression object with the given name, or <tt>null</tt> if no
	 * 		   expression object matches.
	 */
	public static ExpressionObjectMethod getExpressionObjectMethod(IJavaProject project,
		List<QName> namespaces, String methodname) {

		loadDialectsFromProject(project);

		for (ExpressionObjectMethod expressionobject: expressionobjectmethods) {
			if (dialectInProject(expressionobject.getDialect(), project) &&
				dialectInNamespace(expressionobject.getDialect(), namespaces) &&
				expressionObjectMethodMatchesName(expressionobject, methodname)) {
				return expressionobject;
			}
		}
		return null;
	}

	/**
	 * Retrieve all expression object methods for the given project, whose names
	 * match the starting pattern.
	 * 
	 * @param project	 The current project.
	 * @param namespaces List of namespaces available at the current point in
	 * 					 the document.
	 * @param pattern	 Start-of-string pattern to match.
	 * @return List of all matching expression object methods.
	 */
	public static List<ExpressionObjectMethod> getExpressionObjectMethods(IJavaProject project,
		List<QName> namespaces, String pattern) {

		loadDialectsFromProject(project);

		ArrayList<ExpressionObjectMethod> matchedexpressionobjects = new ArrayList<ExpressionObjectMethod>();
		for (ExpressionObjectMethod expressionobjectmethod: expressionobjectmethods) {
			Dialect dialect = expressionobjectmethod.getDialect();
			if (dialectInProject(dialect, project) &&
				dialectInNamespace(dialect, namespaces) &&
				expressionObjectMethodMatchesPattern(expressionobjectmethod, pattern)) {
				matchedexpressionobjects.add(expressionobjectmethod);
			}
		}
		return matchedexpressionobjects;
	}

	/**
	 * Retrieve the processor with the full matching name.
	 * 
	 * @param project		The current project.
	 * @param namespaces	List of namespaces available at the current point in
	 * 						the document.
	 * @param processorname	Full name of the processor.
	 * @return Processor for the given prefix and name, or <tt>null</tt> if no
	 * 		   processor matches.
	 */
	public static Processor getProcessor(IJavaProject project, List<QName> namespaces, String processorname) {

		loadDialectsFromProject(project);

		for (Processor processor: processors) {
			Dialect dialect = processor.getDialect();
			if (dialectInProject(dialect, project) &&
				dialectInNamespace(processor.getDialect(), namespaces) &&
				processorMatchesName(processor, processorname)) {
				return processor;
			}
		}
		return null;
	}

	/**
	 * Retrieve all processors of the given type, for the given project, and
	 * whose names match the starting pattern.
	 * 
	 * @param project	 The current project.
	 * @param namespaces List of namespaces available at the current point in
	 * 					 the document.
	 * @param pattern	 Start-of-string pattern to match.
	 * @param type		 Processor type to retrieve.
	 * @param <P>		 Processor type.
	 * @return List of all matching processors.
	 */
	@SuppressWarnings("unchecked")
	private static <P extends Processor> List<P> getProcessors(IJavaProject project,
		List<QName> namespaces, String pattern, Class<P> type) {

		loadDialectsFromProject(project);

		ArrayList<P> matchedprocessors = new ArrayList<P>();
		for (Processor processor: processors) {
			Dialect dialect = processor.getDialect();
			if (processor.getClass() == type &&
				dialectInProject(dialect, project) &&
				dialectInNamespace(dialect, namespaces) &&
				processorMatchesPattern(processor, pattern)) {
				matchedprocessors.add((P)processor);
			}
		}
		return matchedprocessors;
	}

	/**
	 * Initialize the cache with the Thymeleaf dialects bundled with this
	 * plugin.
	 */
	public static void initialize() {

		logInfo("Loading bundled dialect files");

		List<Dialect> dialects = xmldialectloader.loadDialects(new BundledDialectLocator());
		for (Dialect dialect: dialects) {
			bundleddialects.add(dialect);
			loadDialectItems(dialect, findCurrentJavaProject());
		}

		// Start a resource listener for workspace changes, acting when any dialect
		// file has been changed
		dialectcacheupdater = new DialectFileChangeListener();
		ResourcesPlugin.getWorkspace().addResourceChangeListener(dialectcacheupdater);
	}

	/**
	 * Puts dialect items into their rightful collections.
	 * 
	 * @param dialect
	 * @param project
	 */
	private static void loadDialectItems(Dialect dialect, IJavaProject project) {

		for (DialectItem dialectitem: dialect.getDialectItems()) {
			if (dialectitem instanceof Processor) {
				Processor processor = (Processor)dialectitem;

				// Generate and save javadocs if no documentation present
				if (!dialectitem.isSetDocumentation() && dialectitem.isSetClazz()) {
					dialectitem.setDocumentation(generateDocumentation(processor, project));
				}
				processors.add(processor);
			}
			else if (dialectitem instanceof ExpressionObjectMethod) {
				expressionobjectmethods.add((ExpressionObjectMethod)dialectitem);
			}
			else if (dialectitem instanceof ExpressionObject) {
				expressionobjectmethods.addAll(generateExpressionObjectMethods(dialect, (ExpressionObject)dialectitem));
			}
		}
	}

	/**
	 * Gather all dialect information from the given project, if we haven't got
	 * information on that project in the first place.
	 * 
	 * @param project Project to scan for dialect information.
	 */
	private static void loadDialectsFromProject(IJavaProject project) {

		if (!projectdialects.containsKey(project)) {

			// Scan for any dialect XML files in the project, including dependencies
			ProjectDependencyDialectLocator projectdialectlocator = new ProjectDependencyDialectLocator(project);
			List<Dialect> dialects = xmldialectloader.loadDialects(projectdialectlocator);
			for (Dialect dialect: dialects) {
				loadDialectItems(dialect, project);
			}
			projectdialects.put(project, dialects);

			// Check against the bundled dialects to see if they're also in the project
			for (Dialect dialect: bundleddialects) {
				try {
					if (project.findType(dialect.getClazz(), new NullProgressMonitor()) != null) {
						projectdialects.get(project).add(dialect);
					}
				}
				catch (JavaModelException ex) {
					logError("Unable to access project information", ex);
				}
			}

			// Update the list of projects to track for changes
			dialectcacheupdater.dialectfilepaths.addAll(projectdialectlocator.getDialectFilePaths());
		}
	}

	/**
	 * Checks if the processor name (prefix:name) matches the given name.
	 * 
	 * @param processor
	 * @param name
	 * @return <tt>true</tt> if the name matches the full processor name.
	 */
	private static boolean processorMatchesName(Processor processor, String name) {

		if (name == null || name.isEmpty()) {
			return false;
		}
		int separatorindex = name.indexOf(':');
		return separatorindex != -1 &&
				processor.getDialect().getPrefix().equals(name.substring(0, separatorindex)) &&
				processor.getName().equals(name.substring(separatorindex + 1));
	}

	/**
	 * Checks if the processor name (prefix:name) matches the given
	 * start-of-string pattern.
	 * 
	 * @param processor
	 * @param pattern
	 * @return <tt>true</tt> if the pattern matches against the processor prefix
	 * 		   and name.
	 */
	private static boolean processorMatchesPattern(Processor processor, String pattern) {

		return pattern != null && processor.getFullName().startsWith(pattern);
	}

	/**
	 * Removes dialect items that are part of the given dialect.
	 * 
	 * @param dialect
	 */
	private static void removeDialectItems(Dialect dialect) {

		removeDialectItems(dialect, processors);
		removeDialectItems(dialect, expressionobjectmethods);
	}

	/**
	 * Removes the given type of dialect items that are part of the given
	 * dialect.
	 * 
	 * @param dialect
	 * @param dialectitems
	 */
	private static void removeDialectItems(Dialect dialect, TreeSet<? extends DialectItem> dialectitems) {

		Iterator<? extends DialectItem> dialectitemiter = dialectitems.iterator();
		while (dialectitemiter.hasNext()) {
			DialectItem dialectitem = dialectitemiter.next();
			if (dialectitem.getDialect().equals(dialect)) {
				dialectitemiter.remove();
			}
		}
	}

	/**
	 * A resource change listener, acting on changes made to any dialect files,
	 * updating the dialect cache as necessary.
	 */
	private static class DialectFileChangeListener implements IResourceChangeListener {

		// Collection of dialect files that will be watched for updates to keep the cache up-to-date
		private final CopyOnWriteArraySet<IPath> dialectfilepaths = new CopyOnWriteArraySet<IPath>();

		private final ExecutorService resourcechangeexecutor = Executors.newSingleThreadExecutor();

		/**
		 * Stops the resource change executor.
		 */
		private void close() {

			resourcechangeexecutor.shutdown();
			try {
				if (resourcechangeexecutor.awaitTermination(5, TimeUnit.SECONDS)) {
					resourcechangeexecutor.shutdownNow();
				}
			}
			catch (InterruptedException ex) {
				// Do nothing
			}
		}

		/**
		 * When notified of a resource change, redirect the work to the change
		 * executor thread so as to not block the event change thread.
		 */
		@Override
		public void resourceChanged(final IResourceChangeEvent event) {

			resourcechangeexecutor.execute(new Runnable() {
				@Override
				public void run() {

					if (event.getType() == POST_CHANGE) {
						IResourceDelta delta = event.getDelta();
						for (final IPath dialectfilepath: dialectfilepaths) {
							IResourceDelta dialectfiledelta = delta.findMember(dialectfilepath);
							if (dialectfiledelta != null) {
								logInfo("Dialect file " + dialectfilepath.lastSegment() + " changed, reloading dialect");

								List<Dialect> updateddialects = xmldialectloader.loadDialects(
										new SingleFileDialectLocator(dialectfilepath));
								for (Dialect updateddialect: updateddialects) {
									IProject dialectfileproject = dialectfiledelta.getResource().getProject();
									IJavaProject javaproject = JavaCore.create(dialectfileproject);
									removeDialectItems(updateddialect);
									loadDialectItems(updateddialect, javaproject);
								}
							}
						}
					}
				}
			});
		}
	}
}
