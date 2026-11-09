import UserMessagingPlatform

/// Cordova wrapper for Google UMP SDK 3.x (Swift API names introduced in 3.0.0).
/// JS API is unchanged: verifyConsent(isAgeConsent, isDebug, testDeviceHashId), forceForm(), reset().
/// Success payload is always { consent, hasShownDialog, formAvailable }.
@objc(Ump)
class Ump: CDVPlugin {

    override func pluginInitialize() {
        super.pluginInitialize()
    }

    // MARK: - JS API

    @objc(verifyConsent:)
    func verifyConsent(command: CDVInvokedUrlCommand) {
        let isAgeConsent = command.arguments.count > 0 ? (command.arguments[0] as? Bool ?? false) : false
        let isDebug = command.arguments.count > 1 ? (command.arguments[1] as? Bool ?? false) : false
        let testDeviceHashId = command.arguments.count > 2 ? (command.arguments[2] as? String) : nil

        let parameters = RequestParameters()
        parameters.isTaggedForUnderAgeOfConsent = isAgeConsent

        if isDebug {
            let debugSettings = DebugSettings()
            debugSettings.geography = .EEA
            // Simulators are test devices automatically. For a real device, pass the
            // hashed id that UMP prints to the Xcode console as testDeviceHashId.
            if let id = testDeviceHashId, !id.isEmpty, id != "null" {
                debugSettings.testDeviceIdentifiers = [id]
            }
            parameters.debugSettings = debugSettings
        }

        Task { @MainActor in
            do {
                try await ConsentInformation.shared.requestConsentInfoUpdate(with: parameters)
            } catch {
                print("[Ump] requestConsentInfoUpdate error: \(error.localizedDescription)")
                self.endError(error.localizedDescription, command: command)
                return
            }

            if ConsentInformation.shared.formStatus == .available {
                await self.loadForm(command: command, forceForm: false)
            } else {
                // No form configured / not required for this user.
                print("[Ump] Consent form not required")
                self.endSuccess(consent: true, hasShownDialog: false, formAvailable: false, command: command)
            }
        }
    }

    @objc(forceForm:)
    func forceForm(command: CDVInvokedUrlCommand) {
        Task { @MainActor in
            await self.loadForm(command: command, forceForm: true)
        }
    }

    @objc(reset:)
    func reset(command: CDVInvokedUrlCommand) {
        ConsentInformation.shared.reset()
        self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK), callbackId: command.callbackId)
    }

    // MARK: - Internals

    @MainActor
    private func loadForm(command: CDVInvokedUrlCommand, forceForm: Bool) async {
        let status = ConsentInformation.shared.consentStatus

        // Same logic as before: show the form when forced or when consent is still required.
        guard forceForm || status == .required else {
            // .obtained / .notRequired / .unknown -> nothing to show
            self.endSuccess(consent: status != .required, hasShownDialog: false, formAvailable: true, command: command)
            return
        }

        do {
            let form = try await ConsentForm.load()
            try await form.present(from: self.viewController)
        } catch {
            print("[Ump] consent form error: \(error.localizedDescription)")
            self.endError(error.localizedDescription, command: command)
            return
        }

        let obtained = ConsentInformation.shared.consentStatus == .obtained
        print(obtained ? "[Ump] User got the form and gave consent" : "[Ump] User got the form and didn't give consent")
        self.endSuccess(consent: obtained, hasShownDialog: true, formAvailable: true, command: command)
    }

    private func endError(_ error: String, command: CDVInvokedUrlCommand) {
        self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error), callbackId: command.callbackId)
    }

    private func endSuccess(consent: Bool, hasShownDialog: Bool, formAvailable: Bool, command: CDVInvokedUrlCommand) {
        let json: [AnyHashable: Any] = [
            "consent": consent,
            "hasShownDialog": hasShownDialog,
            "formAvailable": formAvailable
        ]
        self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: json), callbackId: command.callbackId)
    }
}
