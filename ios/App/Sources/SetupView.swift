import SwiftUI
import MailKit

struct SetupView: View {
    @EnvironmentObject var session: MailSession
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false

    private var preset: ProviderPreset? { Providers.forEmail(email) }

    var body: some View {
        Form {
            Section("Account") {
                TextField("Email address", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password or app password", text: $password)
                    .textContentType(.password)
            }
            if let p = preset {
                Section(p.name) {
                    ForEach(p.setupSteps, id: \.self) { Text($0).font(.callout) }
                    if let host = p.imap?.host {
                        Text("Connects directly to \(host). Nothing goes through any other server.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            if let e = session.errorText {
                Section { Text(e).foregroundStyle(.primary).font(.callout) } header: { Text("Problem") }
            }
            Section {
                Button(busy ? "Signing in\u{2026}" : "Sign in") {
                    busy = true
                    Task { await session.signIn(email: email, password: password); busy = false }
                }
                .disabled(busy || email.isEmpty || password.isEmpty)
            } footer: {
                Text("Your password is stored only in this device's Keychain and sent only to your mail provider. No analytics, no telemetry.")
            }
        }
        .navigationTitle("You've got Mail")
    }
}
