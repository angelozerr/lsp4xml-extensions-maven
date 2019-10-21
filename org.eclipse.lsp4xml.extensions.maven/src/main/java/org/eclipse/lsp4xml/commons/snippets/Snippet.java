package org.eclipse.lsp4xml.commons.snippets;

import java.util.List;

public class Snippet {

	private String prefix;

	private List<String> body;

	private String description;

	private String scope;

	private SnippetContext context;

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	public List<String> getBody() {
		return body;
	}

	public void setBody(List<String> body) {
		this.body = body;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public SnippetContext getContext() {
		return context;
	}

	public void setContext(SnippetContext context) {
		this.context = context;
	}

}
