/**
 * Curated catalog of one-click MCP servers shown on the MCP connections page.
 *
 * Each entry maps to the MateClaw backend's MCP server schema:
 *   - HTTP-based remote MCPs use transport = 'streamable_http' with a `url`
 *     and optional bearer-style `headersJson` (placeholders the user replaces).
 *   - Stdio MCPs use transport = 'stdio' with `command`, `argsJson`, and
 *     optional `envJson` (placeholders for API keys).
 *
 * When a user clicks a catalog card the McpServers.vue create modal is
 * pre-filled with these fields; credential placeholders like
 * `YOUR_API_KEY` are kept visible so the user knows what to swap.
 */
export interface McpCredentialKey {
  /** Env var or header name (e.g. CONTEXT7_API_KEY, Authorization). */
  key: string
  /** Whether the server fails to connect when this key is unset. */
  required: boolean
}

export interface McpCatalogEntry {
  /** Stable slug used as the default server name. */
  key: string
  /** Human-readable name shown on the card. */
  name: string
  /** One-line capability description shown under the name. */
  description: string
  /** Official docs link, opened from the card hover external-link icon. */
  docsUrl: string
  /** Pre-fill payload for the create-MCP form. */
  config:
    | {
        transport: 'streamable_http' | 'sse'
        url: string
        headersJson?: string
      }
    | {
        transport: 'stdio'
        command: string
        argsJson?: string
        envJson?: string
      }
  /** Which env vars / headers in the config need user-supplied secrets. */
  credentialKeys?: McpCredentialKey[]
}

export const mcpCatalog: McpCatalogEntry[] = [
  {
    key: 'context7',
    name: 'Context7',
    description: 'Fetch up-to-date library docs and code examples',
    docsUrl: 'https://github.com/upstash/context7',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.context7.com/mcp',
      headersJson: JSON.stringify({ CONTEXT7_API_KEY: 'YOUR_API_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'CONTEXT7_API_KEY', required: false }],
  },
  {
    key: 'figma',
    name: 'Figma',
    description: 'Generate diagrams and better code from Figma context',
    docsUrl: 'https://help.figma.com/hc/en-us/articles/32132100833559',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.figma.com/mcp',
    },
  },
  {
    key: 'linear',
    name: 'Linear',
    description: 'Manage issues, projects and team workflows in Linear',
    docsUrl: 'https://linear.app/docs/mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.linear.app/mcp',
    },
  },
  {
    key: 'notion',
    name: 'Notion',
    description: 'Search, update and power workflows across your Notion workspace',
    docsUrl: 'https://developers.notion.com/docs/mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.notion.com/mcp',
    },
  },
  {
    key: 'slack',
    name: 'Slack',
    description: 'Send messages, create canvases and fetch Slack data',
    docsUrl: 'https://docs.slack.dev/ai/mcp-server',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.slack.com/mcp',
    },
  },
  {
    key: 'supabase',
    name: 'Supabase',
    description: 'Manage databases, authentication and storage',
    docsUrl: 'https://supabase.com/docs/guides/getting-started/mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.supabase.com/mcp',
    },
  },
  {
    key: 'vercel',
    name: 'Vercel',
    description: 'Analyze, debug and manage projects and deployments',
    docsUrl: 'https://vercel.com/docs/mcp/vercel-mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.vercel.com',
    },
  },
  {
    key: 'sentry',
    name: 'Sentry',
    description: 'Search, query and debug errors intelligently',
    docsUrl: 'https://docs.sentry.io/product/sentry-mcp/',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.sentry.dev/mcp',
      headersJson: JSON.stringify({ SENTRY_ACCESS_TOKEN: 'YOUR_ACCESS_TOKEN' }, null, 2),
    },
    credentialKeys: [{ key: 'SENTRY_ACCESS_TOKEN', required: true }],
  },
  {
    key: 'stripe',
    name: 'Stripe',
    description: 'Payment processing and financial infrastructure tools',
    docsUrl: 'https://docs.stripe.com/mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.stripe.com',
      headersJson: JSON.stringify({ STRIPE_SECRET_KEY: 'YOUR_SECRET_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'STRIPE_SECRET_KEY', required: true }],
  },
  {
    key: 'atlassian',
    name: 'Atlassian',
    description: 'Access Jira and Confluence from your agent',
    docsUrl: 'https://www.atlassian.com/platform/remote-mcp-server',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.atlassian.com/v1/mcp',
    },
  },
  {
    key: 'cloudflare',
    name: 'Cloudflare',
    description: 'Build with compute, storage and AI on the Cloudflare platform',
    docsUrl: 'https://developers.cloudflare.com/agents/model-context-protocol/',
    config: {
      transport: 'streamable_http',
      url: 'https://bindings.mcp.cloudflare.com/mcp',
    },
  },
  {
    key: 'huggingface',
    name: 'Hugging Face',
    description: 'Access the Hugging Face Hub and thousands of Gradio apps',
    docsUrl: 'https://huggingface.co/settings/mcp',
    config: {
      transport: 'streamable_http',
      url: 'https://huggingface.co/mcp',
    },
  },
  {
    key: 'posthog',
    name: 'PostHog',
    description: 'Query, analyze and manage your PostHog insights',
    docsUrl: 'https://posthog.com/docs/model-context-protocol',
    config: {
      transport: 'streamable_http',
      url: 'https://mcp.posthog.com/mcp',
    },
  },
  {
    key: 'playwright',
    name: 'Playwright',
    description: 'Browser automation with Playwright',
    docsUrl: 'https://github.com/microsoft/playwright-mcp',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['@playwright/mcp@latest']),
    },
  },
  {
    key: 'chrome-devtools',
    name: 'Chrome DevTools',
    description: 'Browser debugging and performance analysis with Chrome DevTools',
    docsUrl: 'https://github.com/ChromeDevTools/chrome-devtools-mcp',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['chrome-devtools-mcp@latest']),
    },
  },
  {
    key: 'exa',
    name: 'Exa',
    description: 'Web search and code context retrieval powered by Exa AI',
    docsUrl: 'https://docs.exa.ai/reference/exa-mcp',
    config: {
      transport: 'stdio',
      command: 'npx',
      argsJson: JSON.stringify(['-y', 'exa-mcp-server', 'tools=web_search_exa,get_code_context_exa']),
      envJson: JSON.stringify({ EXA_API_KEY: 'YOUR_API_KEY' }, null, 2),
    },
    credentialKeys: [{ key: 'EXA_API_KEY', required: true }],
  },
]

/** Two-letter / single-letter initial for the fallback icon bubble. */
export function catalogInitial(entry: { name: string }): string {
  const ch = entry.name.trim().charAt(0)
  return ch ? ch.toUpperCase() : '?'
}

/** Stable color hash for the catalog icon background. */
export function catalogColor(key: string): string {
  // 8 evenly-spaced HSL hues; pick by simple character sum for determinism.
  const palette = [
    '#e0e7ff', // indigo-100
    '#fce7f3', // pink-100
    '#dcfce7', // green-100
    '#fef3c7', // amber-100
    '#dbeafe', // blue-100
    '#f3e8ff', // purple-100
    '#ffedd5', // orange-100
    '#cffafe', // cyan-100
  ]
  let sum = 0
  for (let i = 0; i < key.length; i++) sum = (sum + key.charCodeAt(i)) >>> 0
  return palette[sum % palette.length]
}
