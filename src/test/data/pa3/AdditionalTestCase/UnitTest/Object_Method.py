class A(object):
    a:int = 1
    x:str = "x in A"
    def set_a(self:"A",b:int):
        self.a = b
    def get_a(self:"A") -> int:
        return self.a
    def set_x(self:"A",b:str):
        self.x = b
    def get_x(self:"A") -> str:
        return self.x
a:A = None
a = A()
print(a.get_a())
print(a.get_x())
a.set_a(5)
a.set_x("hello word")
print(a.get_a())
print(a.get_x())

