import Foundation

/// The seam the on-device model runtime plugs into (mirrors `LlmEngine` in :mail-ai).
///
/// iOS ships with NO engine bundled and none selected: `NoModelEngine` is the honest default, which
/// makes every caller fall through to the heuristics (OTP, categories, summaries picked from the
/// message itself). A Core ML / MLX / llama.cpp engine is a later, owner-prioritised addition and is
/// deliberately not faked here. Cloud (BYOK) engines are likewise a later step and must be opt-in per
/// task and visibly marked as a "watched object".
public protocol LlmEngine: Sendable {
    var id: String { get }
    /// Where inference runs; the UI shows this next to every model-written line.
    var locality: LlmLocality { get }
    func complete(prompt: String, maxTokens: Int) async throws -> String
}

public enum LlmLocality: Sendable { case onDevice, cloud }

public enum LlmError: Error, Equatable { case noModelInstalled }

public struct NoModelEngine: LlmEngine {
    public let id = "none"
    public let locality = LlmLocality.onDevice
    public init() {}
    public func complete(prompt: String, maxTokens: Int) async throws -> String { throw LlmError.noModelInstalled }
}
