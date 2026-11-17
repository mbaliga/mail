import SwiftUI
import MailKit

@main
struct YouveGotMailApp: App {
    @StateObject private var session = MailSession()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var session: MailSession

    var body: some View {
        if session.account == nil {
            NavigationStack { SetupView() }
        } else {
            NavigationStack { InboxView() }
        }
    }
}
