import { afterEach } from 'vitest'

if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.setAttribute('open', '')
  }
}
if (!HTMLDialogElement.prototype.close) {
  HTMLDialogElement.prototype.close = function close() {
    this.removeAttribute('open')
  }
}

afterEach(() => {
  document.body.innerHTML = ''
  localStorage.clear()
})
