# Theme sources

Graphite and Porcelain adapt upstream color palettes to the shared `PlaygroundTheme` roles. Their definitions are bundled with the package; building or selecting a theme does not fetch upstream files.

| Palette   | Source                                                                                                                                                                                                                                                                                                                                                                                  |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Graphite  | VS Code Dark+: [Dark+ token colors](https://github.com/microsoft/vscode/blob/fb20064c0f4bda98ede67f594727503329370d1f/extensions/theme-defaults/themes/dark_plus.json) and [base colors](https://github.com/microsoft/vscode/blob/fb20064c0f4bda98ede67f594727503329370d1f/extensions/theme-defaults/themes/dark_vs.json).                                                              |
| Porcelain | GitHub Light Default: [theme rules](https://github.com/primer/github-vscode-theme/blob/cd78e5e4e7bcf132a6f428ae0f32264bb1b729cf/src/theme.js), [color overrides](https://github.com/primer/github-vscode-theme/blob/cd78e5e4e7bcf132a6f428ae0f32264bb1b729cf/src/colors.js), and [Primer 7.10.0 light colors](https://unpkg.com/@primer/primitives@7.10.0/dist/json/colors/light.json). |

The mapping uses the playground's standard token categories. Language-specific TextMate scopes and upstream editor layout rules are not part of these palettes.

Graphite retains Dark+'s charcoal editor, blue keywords, teal types, yellow functions, and orange strings. The shared accent uses its keyword blue so links and controls remain readable on every surface. Comments are slightly brighter, and the selection background is darker than VS Code's blue selection.

Porcelain retains GitHub's white editor, gray chrome, red keywords, purple functions, and deep blue strings. Its shared accent uses GitHub's link blue. Comments and warning labels are darker; hover and selection backgrounds are lighter. These adjustments preserve at least 4.5:1 contrast for text, token colors on selections, and filled button labels under the shared theme contract.

## Upstream licenses

VS Code, GitHub's VS Code themes, and Primer primitives are distributed under the MIT License. The corresponding copyright notices are:

```text
Copyright (c) 2015 - present Microsoft Corporation
Copyright (c) 2020 Primer
Copyright (c) 2018 GitHub Inc.
```

The following license applies to the adapted upstream palette data:

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
