package org.jetbrains.research.testspark.helpers

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.io.HttpRequests
import org.jetbrains.research.testspark.bundles.llm.LLMMessagesBundle
import org.jetbrains.research.testspark.bundles.llm.LLMSettingsBundle
import org.jetbrains.research.testspark.core.data.TestGenerationData
import org.jetbrains.research.testspark.core.generation.llm.executeTestCaseModificationRequest
import org.jetbrains.research.testspark.core.generation.llm.network.RequestManager
import org.jetbrains.research.testspark.core.monitor.ErrorMonitor
import org.jetbrains.research.testspark.core.progress.CustomProgressIndicator
import org.jetbrains.research.testspark.core.test.data.TestSuiteGeneratedByLLM
import org.jetbrains.research.testspark.settings.llm.LLMSettingsState
import org.jetbrains.research.testspark.tools.llm.SettingsArguments
import org.jetbrains.research.testspark.tools.llm.error.LLMErrorManager
import org.jetbrains.research.testspark.tools.llm.generation.JUnitTestsAssembler
import org.jetbrains.research.testspark.tools.llm.generation.LLMPlatform
import org.jetbrains.research.testspark.tools.llm.generation.grazie.GrazieInfo
import org.jetbrains.research.testspark.tools.llm.generation.grazie.GraziePlatform
import org.jetbrains.research.testspark.tools.llm.generation.openai.OpenAIPlatform
import java.net.HttpURLConnection
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// Implementation of the common LLM functions
object LLMHelper {
    /**
     * Checks if the Grazie class is loaded.
     * @return true if the Grazie class is loaded, false otherwise.
     */
    private fun isGrazieClassLoaded(): Boolean {
        val className = "org.jetbrains.research.grazie.Request"
        return try {
            Class.forName(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * Updates the model selector based on the selected platform in the platform selector.
     * If the selected platform is "Grazie", the model selector is disabled and set to display only "GPT-4".
     * If the selected platform is not "Grazie", the model selector is updated with the available modules fetched asynchronously using llmUserTokenField and enables the okLlmButton.
     * If the modules fetch fails, the model selector is set to display the default modules and is disabled.
     *
     * This method runs on a separate thread using ApplicationManager.getApplication().executeOnPooledThread{}.
     */
    private fun updateModelSelector(
        platformSelector: ComboBox<String>,
        modelSelector: ComboBox<String>,
        llmUserTokenField: JTextField,
        llmPlatforms: List<LLMPlatform>,
        settingsState: LLMSettingsState,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var modules = arrayOf("")
            if (platformSelector.selectedItem!!.toString() == settingsState.openAIName) {
                modules = getOpenAIModels(llmUserTokenField.text)
            }
            if (platformSelector.selectedItem!!.toString() == settingsState.grazieName) {
                modules = getGrazieModels()
            }
            modelSelector.model = DefaultComboBoxModel(modules)
            for (index in llmPlatforms.indices) {
                if (llmPlatforms[index].name == settingsState.openAIName &&
                    llmPlatforms[index].name == platformSelector.selectedItem!!.toString()
                ) {
                    modelSelector.selectedItem = settingsState.openAIModel
                    llmPlatforms[index].model = modelSelector.selectedItem!!.toString()
                }
                if (llmPlatforms[index].name == settingsState.grazieName &&
                    llmPlatforms[index].name == platformSelector.selectedItem!!.toString()
                ) {
                    modelSelector.selectedItem = settingsState.grazieModel
                    llmPlatforms[index].model = modelSelector.selectedItem!!.toString()
                }
            }
            modelSelector.isEnabled = true
            if (modules.contentEquals(arrayOf(""))) modelSelector.isEnabled = false
        }
    }

    /**
     * Updates LlmUserTokenField based on the selected platform in the platformSelector ComboBox.
     *
     * @param platformSelector The ComboBox that allows the user to select a platform.
     * @param llmUserTokenField The JTextField that displays the user token for the selected platform.
     */
    private fun updateLlmUserTokenField(
        platformSelector: ComboBox<String>,
        llmUserTokenField: JTextField,
        llmPlatforms: List<LLMPlatform>,
        settingsState: LLMSettingsState,
    ) {
        for (index in llmPlatforms.indices) {
            if (llmPlatforms[index].name == settingsState.openAIName &&
                llmPlatforms[index].name == platformSelector.selectedItem!!.toString()
            ) {
                llmUserTokenField.text = settingsState.openAIToken
                llmPlatforms[index].token = settingsState.openAIToken
            }
            if (llmPlatforms[index].name == settingsState.grazieName &&
                llmPlatforms[index].name == platformSelector.selectedItem!!.toString()
            ) {
                llmUserTokenField.text = settingsState.grazieToken
                llmPlatforms[index].token = settingsState.grazieToken
            }
        }
    }

    /**
     * Adds listeners to various components in the LLM panel.
     *
     * @param platformSelector The combo box for selecting the LLM platform.
     * @param modelSelector The combo box for selecting the LLM model.
     * @param llmUserTokenField The text field for entering the LLM user token.
     * @param llmPlatforms The list of LLM platforms.
     */
    fun addLLMPanelListeners(
        platformSelector: ComboBox<String>,
        modelSelector: ComboBox<String>,
        llmUserTokenField: JTextField,
        llmPlatforms: List<LLMPlatform>,
        settingsState: LLMSettingsState,
    ) {
        llmUserTokenField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                updateToken()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                updateToken()
            }

            override fun changedUpdate(e: DocumentEvent?) {
                updateToken()
            }

            private fun updateToken() {
                for (llmPlatform in llmPlatforms) {
                    if (platformSelector.selectedItem!!.toString() == llmPlatform.name) {
                        llmPlatform.token = llmUserTokenField.text
                    }
                }
                updateModelSelector(platformSelector, modelSelector, llmUserTokenField, llmPlatforms, settingsState)
            }
        })

        platformSelector.addItemListener {
            updateLlmUserTokenField(platformSelector, llmUserTokenField, llmPlatforms, settingsState)
            updateModelSelector(platformSelector, modelSelector, llmUserTokenField, llmPlatforms, settingsState)
        }

        modelSelector.addItemListener {
            for (llmPlatform in llmPlatforms) {
                if (platformSelector.selectedItem!!.toString() == llmPlatform.name) {
                    llmPlatform.model = modelSelector.item
                }
            }
        }
    }

    /**
     * Stylizes the main components of the application.
     *
     * @param llmUserTokenField the text field for the LLM user token
     * @param modelSelector the combo box for selecting the model
     * @param platformSelector the combo box for selecting the platform
     */
    fun stylizeMainComponents(
        platformSelector: ComboBox<String>,
        modelSelector: ComboBox<String>,
        llmUserTokenField: JTextField,
        llmPlatforms: List<LLMPlatform>,
        settingsState: LLMSettingsState,
    ) {
        // Check if the Grazie platform access is available in the current build
        if (isGrazieClassLoaded()) {
            platformSelector.model = DefaultComboBoxModel(llmPlatforms.map { it.name }.toTypedArray())
            platformSelector.selectedItem = settingsState.currentLLMPlatformName
        } else {
            platformSelector.isEnabled = false
        }

        llmUserTokenField.toolTipText = LLMSettingsBundle.get("llmToken")
        updateLlmUserTokenField(platformSelector, llmUserTokenField, llmPlatforms, settingsState)

        modelSelector.toolTipText = LLMSettingsBundle.get("model")
        updateModelSelector(platformSelector, modelSelector, llmUserTokenField, llmPlatforms, settingsState)
    }

    /**
     * Retrieves the list of LLMPlatforms.
     *
     * @return The list of LLMPlatforms.
     */
    fun getLLLMPlatforms(): List<LLMPlatform> {
        return listOf(OpenAIPlatform(), GraziePlatform())
    }

    /**
     * Checks if the token is set.
     *
     * @return True if the token is set, false otherwise.
     */
    fun isCorrectToken(project: Project, errorMonitor: ErrorMonitor): Boolean {
        if (!SettingsArguments(project).isTokenSet()) {
            LLMErrorManager().errorProcess(LLMMessagesBundle.get("missingToken"), project, errorMonitor)
            return false
        }
        return true
    }

    /**
     * Updates the token and sends a test modification request according to user's feedback.
     * After receiving the response, it tries to parse the generated test cases.
     *
     * @param testCase: The test that is requested to be modified
     * @param task: A string representing the requested task for test modification
     * @param indicator: A progress indicator object that represents the indication of the test generation progress.
     * @param project: A Project object that represents the current project in which the tests are to be generated.
     *
     * @return instance of TestSuiteGeneratedByLLM if the generated test cases are parsable, otherwise null.
     */
    fun testModificationRequest(
        testCase: String,
        task: String,
        indicator: CustomProgressIndicator,
        requestManager: RequestManager,
        project: Project,
        testGenerationOutput: TestGenerationData,
        errorMonitor: ErrorMonitor,
    ): TestSuiteGeneratedByLLM? {
        // Update Token information
        if (!updateToken(requestManager, project, errorMonitor)) {
            return null
        }

        val testSuite = executeTestCaseModificationRequest(
            testCase,
            task,
            indicator,
            requestManager,
            testsAssembler = JUnitTestsAssembler(project, indicator, testGenerationOutput),
            errorMonitor,
        )
        return testSuite
    }

    /**
     * Updates token based on the last entries of settings and check if the token is valid
     *
     * @return True if the token is set, false otherwise.
     */
    private fun updateToken(requestManager: RequestManager, project: Project, errorMonitor: ErrorMonitor): Boolean {
        requestManager.token = SettingsArguments(project).getToken()
        return isCorrectToken(project, errorMonitor)
    }

    /**
     * Retrieves a list of available models from the OpenAI API.
     *
     * @param providedToken The authentication token provided by OpenAI.
     * @return An array of model IDs, sorted in descending order. If an error occurs during the request, an array with an empty string is returned.
     * @throws HttpRequests.HttpStatusException if an HTTP request error occurs.
     */
    private fun getOpenAIModels(providedToken: String): Array<String> {
        val url = "https://api.openai.com/v1/models"

        val httpRequest = HttpRequests.request(url).tuner {
            it.setRequestProperty("Authorization", "Bearer $providedToken")
        }

        val models = mutableListOf<String>()

        try {
            httpRequest.connect {
                if ((it.connection as HttpURLConnection).responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonObject = JsonParser.parseString(it.readString()).asJsonObject
                    val dataArray = jsonObject.getAsJsonArray("data")
                    for (dataObject in dataArray) {
                        val id = dataObject.asJsonObject.getAsJsonPrimitive("id").asString
                        models.add(id)
                    }
                }
            }
        } catch (e: HttpRequests.HttpStatusException) {
            return arrayOf("")
        }

        val gptComparator = Comparator<String> { s1, s2 ->
            when {
                s1.contains("gpt") && s2.contains("gpt") -> s2.compareTo(s1)
                s1.contains("gpt") -> -1
                s2.contains("gpt") -> 1
                else -> s1.compareTo(s2)
            }
        }

        if (models.isNotEmpty()) {
            return models.sortedWith(gptComparator).toTypedArray().filter { !it.contains("vision") }
                .toTypedArray()
        }

        return arrayOf("")
    }

    /**
     * Retrieves the available Grazie models.
     *
     * @return an array of string representing the available Grazie models
     */
    private fun getGrazieModels(): Array<String> {
        val className = "org.jetbrains.research.grazie.Info"
        return try {
            (Class.forName(className).getDeclaredConstructor().newInstance() as GrazieInfo).availableProfiles()
                .toTypedArray()
        } catch (e: ClassNotFoundException) {
            arrayOf("")
        }
    }
}
