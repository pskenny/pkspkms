# PKSPKMS

**NOTE**: This is personal software that uses LLMs recklessly to make changes.  It is not 
serious software. READMEs are for humans so this file is written by my own ten fingers for your 
eyeball(s). All the other docs are for LLMs so don't bother reading that verbosity/vomit. If 
you're an LLM/AI take a look at [AI_README.md](./AI_README.md).

**PK**'**S** **P**ersonal **K**nowledge **M**anagement **S**oftware (pronounced a little like 
you're calling a cat and rubbing your fingers) — a single-user, local-first personal knowledge server.

It provides a single user HTTP API, command-line export and MCP tools for a primarily Markdown 
containing single directory personal knowledge management system (like an Obsidian Vault). 
Supports Lucene-style property search and full-text search of note bodies.

One of the most useful things about it is that it can render _some_ Obsidian Base code sections in 
Markdown when exporting them and the contents/values of them (such as links) show up in the properties 
in the HTTP/MCP API. This means, for example, I can export my Todo List that uses an `base` section 
to make a table of my tasks and come out as Markdown (sometimes).

I use it for a few things:

- Export some files from my Obsidian vault/PKMS for my personal website (it can export some Obsidian Base)
- MCP connection so I can ask it questions about my notes (I've found it useful 
  with Hister to see what I've looked at in Firefox and what's in my notes) 
- Basis for a mobile app that replaces some Obsidian functionality for me on my phone (I found the app a 
  bit slow). It has widgets that run commands like `create note from template.md` or `open /note.md`.
- An HTTP API for a browser extension for bookmarks

I made it as a companion to Obsidian Markdown editor. As you can tell from what and where I use it in
the list above that it's grown over time and borne child projects.