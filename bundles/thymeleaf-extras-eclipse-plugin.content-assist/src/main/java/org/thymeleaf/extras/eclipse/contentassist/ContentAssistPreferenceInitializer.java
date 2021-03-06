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

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import static org.thymeleaf.extras.eclipse.contentassist.ContentAssistPlugin.*;

/**
 * Initializes preferences for this plugin.
 * 
 * @author Emanuel Rabina
 */
public class ContentAssistPreferenceInitializer extends AbstractPreferenceInitializer {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void initializeDefaultPreferences() {

		IPreferenceStore preferences = getDefault().getPreferenceStore();
		preferences.setDefault(AUTO_PROPOSE_PREF, true);
	}
}
