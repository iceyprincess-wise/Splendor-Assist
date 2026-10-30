enum class CorrectionType { PASS, SHOT, CROSS, KEEPER, NONE }

/**
 * SmartAssistUltimateCorrectorEngine
 *
 * Core mathematical engine for fixing Smart Assist drift across all action types.
 * Performs zero heap allocations during math evaluations.
 */
