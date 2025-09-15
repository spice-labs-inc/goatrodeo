## Contributing to This Project

Thank you for your interest in this project. We welcome all kinds of contributions: code, documentation, bug reports, feature requests, and more. This guide will help you get started. We ask that you engage in good faith, honesty, and integrity, and respect that the maintainers make the final decisions on this project.

---

### Reporting Bugs

Please include:
- A clear, descriptive title
- Steps to reproduce the issue
- What you expected to happen vs. what actually happened
- Any relevant logs, screenshots, or files
- Your environment (OS, version of the software you are using or trying to use, how it was installed, etc.)

Open a [new issue](../../issues/new) to report the problem.

---

### Suggesting Features

Please include:
- A summary of the problem you are trying to solve
- Why it is important or useful
- A rough idea of how it could be implemented

Open a [new issue](../../issues/new) to make the suggestion.

---

### Making Code Contributions

Follow the coding style used in the project:
- The project has linters and formatters configured, so use them
- Add or update tests if you change features or handle new cases
- Run existing tests to ensure everything still works
- Write clear commit messages that describe the changes:

   ```bash
   git commit -m "Handle timeouts in API client with retry backoff"
   git push origin fix/handle-timeouts
   ```
- Longer commit messages are welcome when describing the approach, alternatives considered, or other useful details
- Please keep commits as focused as possible. It is better to have two smaller commits for unrelated changes than one combined commit with a vague title such as "updates" or "changes"

---

### Tests and CI

- Run the tests and linters against your changes
- Reach out if you are unsure how to run the tests

---

### Opening a Pull Request

- Open your pull request against the `next` branch. We integrate changes into `next` before cutting releases to `main` and creating tagged releases
- Use the pull request template as a guide. Explain why you are making changes as clearly as you can
- Link to any related issues
- Be ready to discuss or make changes after review
- Ensure CI passes on your PR and make any required changes

---

### Collaborating and Reviewing

We use pull request reviews to discuss changes:
- Code improvements or simplifications
- Better naming or comments
- Test coverage or performance notes
- Alternate approaches
- Formatting or documentation corrections

---

### Licensing

All contributions must be compatible with the project’s [license](LICENSE.txt), and you must have the legal right to contribute them. By submitting code, you agree to license it under the same terms.

---

### Thank You!

Thank you for contributing. Whether you are fixing a typo, suggesting a feature, or rewriting a core component, your contribution helps and is greatly appreciated.  

For questions, feel free to open an issue or start a discussion. Community discussions for Spice Labs open source projects are on Matrix at https://matrix.to/#/#spice-labs:matrix.org

---
