#!/usr/bin/env node
import { createConnection } from "vscode-languageserver/node";
import { serveCp } from "./server.js";

serveCp(createConnection());
