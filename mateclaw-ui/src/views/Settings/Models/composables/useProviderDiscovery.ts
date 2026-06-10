import { computed, reactive, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { modelApi, settingsApi } from '@/api'
import type { DiscoverResult, ModelConfig, ProviderInfo, ProviderModelInfo, TestResult } from '@/types'

export interface ModelConfigDraft {
  name: string
  description: string
  temperature: string
  maxTokens: string
  maxInputTokens: string
  topP: string
  requestTimeoutSeconds: string
  enableSearch: boolean
  searchStrategy: string
}

interface ListDeps {
  currentProvider: Ref<ProviderInfo | null>
  refreshCurrentProvider: (providerId: string) => Promise<void>
}

/**
 * RFC-074 PR-1: manage-models modal + per-provider model discovery and
 * connection / model testing. All testing state (connectionResults,
 * modelTestResults) lives here so the cards can render results without the
 * list composable knowing about probes.
 */
export function useProviderDiscovery(deps: ListDeps) {
  const { t } = useI18n()

  const showManageModelsModal = ref(false)
  const defaultMaxInputTokens = ref<number | null>(null)
  const providerModelForm = reactive({
    id: '',
    name: '',
    maxInputTokens: '',
  })
  const modelInputWindowDrafts = reactive<Record<string, string>>({})
  const savingInputWindowModelId = ref<string | number | null>(null)
  const selectedModelConfigId = ref<string | number | null>(null)
  const selectedModelConfigLoading = ref(false)
  const selectedModelConfigSaving = ref(false)
  const selectedModelConfigDetail = ref<ModelConfig | null>(null)
  const modelConfigDraft = reactive<ModelConfigDraft>({
    name: '',
    description: '',
    temperature: '',
    maxTokens: '',
    maxInputTokens: '',
    topP: '',
    requestTimeoutSeconds: '',
    enableSearch: false,
    searchStrategy: '',
  })

  const discovering = ref(false)
  const discoverResult = ref<DiscoverResult | null>(null)
  const selectedNewModelIds = ref<string[]>([])
  const applyingModels = ref(false)

  const connectionTestingId = ref<string | null>(null)
  const connectionResults = ref<Record<string, TestResult>>({})
  const testingModelId = ref<string | null>(null)
  const modelTestResults = ref<Record<string, TestResult>>({})

  function modelDraftKey(model: ProviderModelInfo) {
    return String(model.configId ?? model.id)
  }

  async function loadDefaultMaxInputTokens() {
    try {
      const res: any = await settingsApi.get()
      const value = Number(res?.data?.defaultMaxInputTokens)
      defaultMaxInputTokens.value = Number.isInteger(value) && value > 0 ? value : null
    } catch {
      defaultMaxInputTokens.value = null
    }
  }

  function prefilledMaxInputTokens(raw?: number | null) {
    if (raw && raw > 0) return String(raw)
    if (defaultMaxInputTokens.value && defaultMaxInputTokens.value > 0) {
      return String(defaultMaxInputTokens.value)
    }
    return ''
  }

  function syncModelInputWindowDrafts(provider: ProviderInfo | null = deps.currentProvider.value) {
    const nextKeys = new Set<string>()
    const models = [...(provider?.models || []), ...(provider?.extraModels || [])]
    for (const model of models) {
      const key = modelDraftKey(model)
      nextKeys.add(key)
      modelInputWindowDrafts[key] = prefilledMaxInputTokens(model.maxInputTokens)
    }
    for (const key of Object.keys(modelInputWindowDrafts)) {
      if (!nextKeys.has(key)) delete modelInputWindowDrafts[key]
    }
  }

  function normalizeDraftText(raw: unknown) {
    return raw == null ? '' : String(raw).trim()
  }

  function normalizeMaxInputTokens(raw: unknown) {
    const text = normalizeDraftText(raw)
    if (!text) {
      throw new Error(t('settings.model.inputWindowInvalid'))
    }
    const value = Number(text)
    if (!Number.isInteger(value) || value <= 0) {
      throw new Error(t('settings.model.inputWindowInvalid'))
    }
    return value
  }

  function formatOptionalNumber(value?: number | null) {
    return value == null ? '' : String(value)
  }

  function resetModelConfigDraft() {
    selectedModelConfigId.value = null
    selectedModelConfigDetail.value = null
    modelConfigDraft.name = ''
    modelConfigDraft.description = ''
    modelConfigDraft.temperature = ''
    modelConfigDraft.maxTokens = ''
    modelConfigDraft.maxInputTokens = ''
    modelConfigDraft.topP = ''
    modelConfigDraft.requestTimeoutSeconds = ''
    modelConfigDraft.enableSearch = false
    modelConfigDraft.searchStrategy = ''
  }

  function fillModelConfigDraft(detail: ModelConfig) {
    modelConfigDraft.name = detail.name || ''
    modelConfigDraft.description = detail.description || ''
    modelConfigDraft.temperature = formatOptionalNumber(detail.temperature)
    modelConfigDraft.maxTokens = formatOptionalNumber(detail.maxTokens)
    modelConfigDraft.maxInputTokens = formatOptionalNumber(detail.maxInputTokens)
    modelConfigDraft.topP = formatOptionalNumber(detail.topP)
    modelConfigDraft.requestTimeoutSeconds = formatOptionalNumber(detail.requestTimeoutSeconds)
    modelConfigDraft.enableSearch = detail.enableSearch === true
    modelConfigDraft.searchStrategy = detail.searchStrategy || ''
  }

  function normalizeOptionalNumber(raw: unknown, label: string) {
    const text = normalizeDraftText(raw)
    if (!text) return null
    const value = Number(text)
    if (!Number.isFinite(value)) {
      throw new Error(t('settings.model.modelParamsNumberInvalid', { field: label }))
    }
    return value
  }

  function normalizeOptionalInteger(raw: unknown, label: string) {
    const text = normalizeDraftText(raw)
    if (!text) return null
    const value = Number(text)
    if (!Number.isInteger(value) || value <= 0) {
      throw new Error(t('settings.model.modelParamsIntegerInvalid', { field: label }))
    }
    return value
  }

  const allNewSelected = computed(() => {
    if (!discoverResult.value || discoverResult.value.newCount === 0) return false
    return selectedNewModelIds.value.length === discoverResult.value.newModels.length
  })

  async function openManageModelsModal(provider: ProviderInfo) {
    if (defaultMaxInputTokens.value == null) {
      await loadDefaultMaxInputTokens()
    }
    deps.currentProvider.value = provider
    providerModelForm.id = ''
    providerModelForm.name = ''
    providerModelForm.maxInputTokens = prefilledMaxInputTokens(null)
    syncModelInputWindowDrafts(provider)
    showManageModelsModal.value = true
  }

  function closeManageModelsModal() {
    showManageModelsModal.value = false
    deps.currentProvider.value = null
    discoverResult.value = null
    selectedNewModelIds.value = []
    modelTestResults.value = {}
    testingModelId.value = null
    savingInputWindowModelId.value = null
    selectedModelConfigLoading.value = false
    selectedModelConfigSaving.value = false
    resetModelConfigDraft()
    providerModelForm.maxInputTokens = ''
    for (const key of Object.keys(modelInputWindowDrafts)) delete modelInputWindowDrafts[key]
  }

  function isExtraModel(modelId: string) {
    return !!deps.currentProvider.value?.extraModels?.some(model => model.id === modelId)
  }

  async function addProviderModel() {
    if (!deps.currentProvider.value || !providerModelForm.id) return
    const maxInputTokens = normalizeMaxInputTokens(providerModelForm.maxInputTokens)
    await modelApi.addProviderModel(deps.currentProvider.value.id, {
      id: providerModelForm.id,
      name: providerModelForm.name || providerModelForm.id,
      maxInputTokens,
    })
    await deps.refreshCurrentProvider(deps.currentProvider.value.id)
    syncModelInputWindowDrafts()
    providerModelForm.id = ''
    providerModelForm.name = ''
    providerModelForm.maxInputTokens = prefilledMaxInputTokens(null)
  }

  async function removeProviderModel(model: ProviderModelInfo) {
    if (!deps.currentProvider.value) return
    if (!confirm(t('settings.model.removeConfirm', { name: model.name }))) return
    await modelApi.removeProviderModel(deps.currentProvider.value.id, model.id)
    await deps.refreshCurrentProvider(deps.currentProvider.value.id)
    syncModelInputWindowDrafts()
  }

  function getModelInputWindowDraft(model: ProviderModelInfo) {
    const key = modelDraftKey(model)
    if (!(key in modelInputWindowDrafts)) {
      modelInputWindowDrafts[key] = prefilledMaxInputTokens(model.maxInputTokens)
    }
    return modelInputWindowDrafts[key]
  }

  function updateModelInputWindowDraft(model: ProviderModelInfo, value: string) {
    modelInputWindowDrafts[modelDraftKey(model)] = value.replace(/[^\d]/g, '')
  }

  async function saveModelInputWindow(model: ProviderModelInfo) {
    if (!deps.currentProvider.value || !model.configId) {
      throw new Error(t('settings.model.inputWindowUnsupported'))
    }
    const key = modelDraftKey(model)
    const maxInputTokens = normalizeMaxInputTokens(modelInputWindowDrafts[key] ?? '')
    savingInputWindowModelId.value = model.configId
    try {
      const detail: any = await modelApi.get(model.configId)
      await modelApi.update(model.configId, {
        ...detail.data,
        maxInputTokens,
      })
      await deps.refreshCurrentProvider(deps.currentProvider.value.id)
      syncModelInputWindowDrafts()
    } finally {
      savingInputWindowModelId.value = null
    }
  }

  async function selectModelConfig(model: ProviderModelInfo) {
    if (!model.configId) {
      mcToast.warning(t('settings.model.modelParamsUnsupported'))
      return
    }
    const configId = model.configId
    selectedModelConfigId.value = configId
    selectedModelConfigLoading.value = true
    try {
      const detail: any = await modelApi.get(configId)
      if (selectedModelConfigId.value !== configId) return
      selectedModelConfigDetail.value = detail.data
      fillModelConfigDraft(detail.data)
    } catch (error) {
      if (selectedModelConfigId.value !== configId) return
      resetModelConfigDraft()
      mcToast.error(error instanceof Error ? error.message : String(error))
    } finally {
      if (selectedModelConfigId.value === configId) {
        selectedModelConfigLoading.value = false
      }
    }
  }

  async function saveSelectedModelConfig() {
    const configId = selectedModelConfigId.value
    if (!deps.currentProvider.value || !configId) return
    const name = modelConfigDraft.name.trim()
    if (!name) {
      throw new Error(t('settings.model.modelParamsNameRequired'))
    }
    selectedModelConfigSaving.value = true
    try {
      const detail: any = await modelApi.get(configId)
      const payload = {
        ...detail.data,
        name,
        description: modelConfigDraft.description.trim(),
        temperature: normalizeOptionalNumber(modelConfigDraft.temperature, t('settings.fields.temperature')),
        maxTokens: normalizeOptionalInteger(modelConfigDraft.maxTokens, t('settings.fields.maxTokens')),
        maxInputTokens: normalizeOptionalInteger(modelConfigDraft.maxInputTokens, t('settings.model.fields.maxInputTokens')),
        topP: normalizeOptionalNumber(modelConfigDraft.topP, t('settings.fields.topP')),
        requestTimeoutSeconds: normalizeOptionalInteger(
          modelConfigDraft.requestTimeoutSeconds,
          t('settings.fields.requestTimeoutSeconds'),
        ),
        enableSearch: modelConfigDraft.enableSearch,
        searchStrategy: modelConfigDraft.searchStrategy.trim(),
      }
      await modelApi.update(configId, payload)
      const saved: any = await modelApi.get(configId)
      selectedModelConfigDetail.value = saved.data
      fillModelConfigDraft(saved.data)
      await deps.refreshCurrentProvider(deps.currentProvider.value.id)
      syncModelInputWindowDrafts()
    } finally {
      selectedModelConfigSaving.value = false
    }
  }

  function toggleSelectAll() {
    if (!discoverResult.value) return
    if (allNewSelected.value) {
      selectedNewModelIds.value = []
    } else {
      selectedNewModelIds.value = discoverResult.value.newModels.map(m => m.id)
    }
  }

  async function handleDiscoverModels() {
    if (!deps.currentProvider.value) return
    discovering.value = true
    discoverResult.value = null
    selectedNewModelIds.value = []
    try {
      const res: any = await modelApi.discoverModels(deps.currentProvider.value.id)
      discoverResult.value = res.data
      if (res.data?.newCount > 0) {
        selectedNewModelIds.value = res.data.newModels.map((m: ProviderModelInfo) => m.id)
      }
    } catch (error) {
      mcToast.error(error instanceof Error ? error.message : String(error))
    } finally {
      discovering.value = false
    }
  }

  async function handleApplyModels() {
    if (!deps.currentProvider.value || selectedNewModelIds.value.length === 0) return 0
    applyingModels.value = true
    try {
      const res: any = await modelApi.applyDiscoveredModels(
        deps.currentProvider.value.id, selectedNewModelIds.value)
      const added = res.data?.added ?? selectedNewModelIds.value.length
      discoverResult.value = null
      selectedNewModelIds.value = []
      await deps.refreshCurrentProvider(deps.currentProvider.value.id)
      return added
    } catch (error) {
      mcToast.error(error instanceof Error ? error.message : String(error))
      return 0
    } finally {
      applyingModels.value = false
    }
  }

  async function handleTestConnection(provider: ProviderInfo) {
    connectionTestingId.value = provider.id
    delete connectionResults.value[provider.id]
    try {
      const res: any = await modelApi.testConnection(provider.id)
      connectionResults.value[provider.id] = res.data
    } catch (error) {
      connectionResults.value[provider.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      connectionTestingId.value = null
    }
  }

  async function handleTestModel(model: ProviderModelInfo) {
    if (!deps.currentProvider.value) return
    testingModelId.value = model.id
    delete modelTestResults.value[model.id]
    try {
      const res: any = await modelApi.testModel(deps.currentProvider.value.id, model.id)
      modelTestResults.value[model.id] = res.data
    } catch (error) {
      modelTestResults.value[model.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      testingModelId.value = null
    }
  }

  void loadDefaultMaxInputTokens()

  return {
    showManageModelsModal,
    defaultMaxInputTokens,
    providerModelForm,
    savingInputWindowModelId,
    selectedModelConfigId,
    selectedModelConfigLoading,
    selectedModelConfigSaving,
    modelConfigDraft,
    discovering,
    discoverResult,
    selectedNewModelIds,
    applyingModels,
    connectionTestingId,
    connectionResults,
    testingModelId,
    modelTestResults,
    allNewSelected,
    openManageModelsModal,
    closeManageModelsModal,
    isExtraModel,
    addProviderModel,
    removeProviderModel,
    getModelInputWindowDraft,
    updateModelInputWindowDraft,
    saveModelInputWindow,
    selectModelConfig,
    saveSelectedModelConfig,
    toggleSelectAll,
    handleDiscoverModels,
    handleApplyModels,
    handleTestConnection,
    handleTestModel,
  }
}
